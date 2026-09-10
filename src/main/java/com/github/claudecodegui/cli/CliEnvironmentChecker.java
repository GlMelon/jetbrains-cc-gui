package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.provider.claude.ClaudeCliDetector;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI环境检查器。
 * 参考cc-switch项目的实现，检测本地安装的AI CLI工具状态。
 */
public class CliEnvironmentChecker {

    private static final Logger LOG = Logger.getInstance(CliEnvironmentChecker.class);

    /**
     * 要检查的CLI工具定义
     */
    private static final CliToolDefinition[] CLI_TOOLS = {
        new CliToolDefinition(
            ProviderType.CLAUDE.value(),
            "Claude CLI",
            "Anthropic Claude 命令行工具",
            "@anthropic-ai/claude-code"
        ),
        new CliToolDefinition(
            ProviderType.CODEX.value(),
            "Codex CLI",
            "OpenAI Codex 命令行工具",
            "@openai/codex"
        ),
        new CliToolDefinition(
            ProviderType.OPENCODE.value(),
            "OpenCode CLI",
            "OpenCode 命令行工具",
            "opencode-ai"
        ),
        new CliToolDefinition(
            ProviderType.GROK.value(),
            "Grok CLI",
            "xAI Grok 命令行工具",
            "@xai-official/grok"
        ),
        new CliToolDefinition(
            ProviderType.KIMI.value(),
            "Kimi CLI",
            "Moonshot Kimi 命令行工具",
            "@moonshot-ai/kimi-code"
        ),
        new CliToolDefinition(
            ProviderType.PI.value(),
            "Pi CLI",
            "Inflection Pi 命令行工具",
            "@earendil-works/pi-coding-agent"
        ),
        // omp:Pi fork(can1357/oh-my-pi,bin=omp)。npm 上 "omp" 是无 bin 的无关占位包、
        // "oh-my-pi" 已被无关项目占用 → npmPackage 置 null(检测/门控照常,仅无
        // 版本检查与一键安装;checkCliEnvironment/installCliTool 均有 null 守卫)。
        new CliToolDefinition(
            ProviderType.OMP.value(),
            "OMP CLI",
            "oh-my-pi 命令行工具(Pi fork)",
            null
        ),
        // dsh:DeepSeek Harness,npm 官方包 @deepseek-ai/dsh(bin=dsh,已 npm view 验证)。
        new CliToolDefinition(
            ProviderType.DSH.value(),
            "DeepSeek Harness CLI",
            "DeepSeek Harness 命令行工具",
            "@deepseek-ai/dsh"
        ),
        // minimax:MiniMax Code,官方安装器(~/.minimax-code)与 npm 官方包
        // @minimax-ai/code 的 bin 均为 `mcode`(官方 install 脚本核实)。⚠️npm 上名为
        // "mcode" 的是无关的 MSON Code 包(github.com/smizell/mcode),版本检查/一键安装
        // 必须用 scoped 包名;探测主名 mcode、防御性回退 minimax(对齐 CliStatusDetector)。
        new CliToolDefinition(
            ProviderType.MINIMAX.value(),
            "MiniMax Code CLI",
            "MiniMax Code 命令行工具",
            "@minimax-ai/code",
            "mcode",
            CliToolId.MINIMAX.getAltBinaryName()
        ),
    };

    /**
     * 版本号正则表达式
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:-[a-zA-Z0-9.]+)?)");

    /**
     * npm 全局安装超时(秒)。claude-code 经 optionalDependencies 携带 ~200MB 平台原生二进制,
     * 首次安装从镜像下载耗时远超 2 分钟;120s 会在下载中途截断安装(npm 下载缓存/临时文件
     * 落在 %LOCALAPPDATA%,用户侧表现为"下载到了 C 盘但 CLI 不可用")。
     */
    private static final long INSTALL_TIMEOUT_SECONDS = 600;

    /**
     * npm 输出收集上限(字符):超限时丢弃头部保留尾部,失败时只透出末尾原因。
     */
    private static final int INSTALL_OUTPUT_LIMIT_CHARS = 8192;

    // ── 检测结果缓存 ──────────────────────────────────────────────
    // 全量检测 = 各 CLI × (可执行文件探测 + --version + npm view),秒级耗时;
    // 供应商下拉/模型列表/供应商页签等常驻 UI 消费安装状态,不能每次都全量重检。
    // TTL 内直接复用缓存;force(用户点击"重新检测/刷新")绕过缓存强制重检。
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final Object CACHE_LOCK = new Object();
    private static volatile List<CliEnvironmentStatus> cachedStatuses;
    private static volatile long cachedAtMillis;

    /**
     * 带缓存的全量检测(在后台线程调用,内部有锁串行化并发请求)。
     *
     * @param force true 时绕过缓存强制重检并更新缓存(用户手动刷新);
     *              false 时 TTL 内直接返回缓存
     */
    public static List<CliEnvironmentStatus> getStatusesCached(boolean force) {
        synchronized (CACHE_LOCK) {
            if (!force && isCacheFresh()) {
                return cachedStatuses;
            }
            List<CliEnvironmentStatus> fresh = new CliEnvironmentChecker().checkAllCliEnvironments();
            cachedStatuses = fresh;
            cachedAtMillis = System.currentTimeMillis();
            return fresh;
        }
    }

    private static boolean isCacheFresh() {
        return cachedStatuses != null
            && (System.currentTimeMillis() - cachedAtMillis) < CACHE_TTL_MS;
    }

    /**
     * 检查所有CLI环境
     */
    public List<CliEnvironmentStatus> checkAllCliEnvironments() {
        List<CliEnvironmentStatus> results = new ArrayList<>();

        for (CliToolDefinition tool : CLI_TOOLS) {
            try {
                CliEnvironmentStatus status = checkCliEnvironment(tool);
                results.add(status);
            } catch (Exception e) {
                LOG.error("[CliEnvironmentChecker] Failed to check " + tool.name, e);
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    tool.name, tool.displayName, tool.description, tool.npmPackage
                );
                errorStatus.setError("检测失败: " + e.getMessage());
                results.add(errorStatus);
            }
        }

        return results;
    }

    /**
     * 检查单个CLI环境
     */
    public CliEnvironmentStatus checkCliEnvironment(CliToolDefinition tool) {
        CliEnvironmentStatus status = new CliEnvironmentStatus(
            tool.name, tool.displayName, tool.description, tool.npmPackage
        );

        try {
            // 1. 查找CLI可执行文件(先主探测名后别名,与 CliStatusDetector 的双名探测一致;
            //    probeName 与 provider 标识解耦——如 minimax 的 provider id 是 "minimax"、
            //    官方可执行名是 "mcode")
            String cliPath = findCliExecutable(tool.probeName, tool.altName);
            
            if (cliPath == null) {
                status.setInstalled(false);
                status.setError("未找到CLI可执行文件");
                return status;
            }

            status.setInstalled(true);
            status.setInstallPath(cliPath);

            // 2. 推断安装来源
            status.setInstallSource(inferInstallSource(cliPath));

            // 3. 获取版本信息
            String version = getVersion(cliPath);
            if (version != null) {
                status.setVersion(version);
            }

            // 4. 获取最新版本（从npm registry）—— 仅当声明了 npm 包名时才查询。
            // 除 omp(npm 无官方包,npmPackage=null)外其余七家均有真实 npmPackage;
            // 判空守卫覆盖非 npm 分发条目(声明为 null/blank):npmPackage=null 会使
            // getLatestVersionFromNpm(null) NPE(ProcessBuilder 拒绝 null 参数),
            // 触发下方 catch 污染整张卡片状态;且对不存在的包每次空等最长 10s。
            String latestVersion = null;
            if (tool.npmPackage != null && !tool.npmPackage.isBlank()) {
                latestVersion = getLatestVersionFromNpm(tool.npmPackage);
            }
            if (latestVersion != null) {
                status.setLatestVersion(latestVersion);

                // 检查是否有更新
                if (version != null) {
                    status.setHasUpdate(!version.equals(latestVersion));
                }
            }

        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Error checking " + tool.name + ": " + e.getMessage());
            status.setError("检测过程中出错: " + e.getMessage());
        }

        return status;
    }

    /**
     * 查找CLI可执行文件(先主名,未命中再探测别名;如 minimax → mcode)
     */
    private String findCliExecutable(String cliName, String altName) {
        String path = findSingleName(cliName);
        if (path == null && altName != null && !altName.isBlank()) {
            path = findSingleName(altName);
        }
        return path;
    }

    private String findSingleName(String cliName) {
        String executableName = PlatformUtils.isWindows() ? cliName + ".cmd" : cliName;

        // 1. 首先尝试在PATH中查找
        String pathResult = findInPath(executableName);
        if (pathResult != null) {
            return pathResult;
        }

        // 2. 在常见安装目录中查找
        List<String> searchDirs = getSearchDirectories();
        for (String dir : searchDirs) {
            File file = new File(dir, executableName);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    /**
     * 在PATH环境变量中查找可执行文件
     */
    private String findInPath(String executableName) {
        try {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) {
                return null;
            }

            String pathSeparator = PlatformUtils.isWindows() ? ";" : ":";
            String[] pathDirs = pathEnv.split(pathSeparator);

            for (String dir : pathDirs) {
                File file = new File(dir, executableName);
                if (file.exists() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Error searching PATH: " + e.getMessage());
        }

        return null;
    }

    /**
     * 获取搜索目录列表
     */
    private List<String> getSearchDirectories() {
        List<String> dirs = new ArrayList<>();
        String userHome = PlatformUtils.getHomeDirectory();

        if (PlatformUtils.isWindows()) {
            // Windows 目录
            String localAppData = System.getenv("LOCALAPPDATA");
            String appData = System.getenv("APPDATA");

            if (localAppData != null) {
                dirs.add(localAppData + "\\npm");
            }
            if (appData != null) {
                dirs.add(appData + "\\npm");
            }
            // minimax 官方安装器目录(install.ps1: %USERPROFILE%\.minimax-code,launcher 在根下)
            String userHomeWin = PlatformUtils.getHomeDirectory();
            dirs.add(userHomeWin + "\\.minimax-code");
            
            // Volta
            String voltaHome = System.getenv("VOLTA_HOME");
            if (voltaHome != null) {
                dirs.add(voltaHome + "\\bin");
            }
            
            // Scoop
            String scoop = System.getenv("SCOOP");
            if (scoop != null) {
                dirs.add(scoop + "\\shims");
            }
        } else {
            // macOS/Linux 目录
            dirs.add(userHome + "/.local/bin");
            dirs.add(userHome + "/.npm-global/bin");
            dirs.add("/usr/local/bin");
            dirs.add("/usr/bin");
            // omp/dsh/minimax 专属安装目录(与 CliStatusDetector 的目录表对齐;
            // dsh 的 Hermes 原生安装器把 node + dsh 一起放在 .hermes/node/bin;
            // minimax 官方安装器(install.sh)launcher 落在 ~/.minimax-code 根下)
            dirs.add(userHome + "/.omp/bin");
            dirs.add(userHome + "/.dsh/bin");
            dirs.add(userHome + "/.hermes/node/bin");
            dirs.add(userHome + "/.minimax-code");
            
            // Volta
            dirs.add(userHome + "/.volta/bin");
            
            // NVM
            File nvmDir = new File(userHome, ".nvm/versions/node");
            if (nvmDir.exists() && nvmDir.isDirectory()) {
                File[] nodeVersions = nvmDir.listFiles(File::isDirectory);
                if (nodeVersions != null) {
                    for (File version : nodeVersions) {
                        dirs.add(version.getAbsolutePath() + "/bin");
                    }
                }
            }
        }

        return dirs;
    }

    /**
     * 推断安装来源
     */
    private String inferInstallSource(String cliPath) {
        String pathLower = cliPath.toLowerCase();
        
        if (pathLower.contains("/.nvm/") || pathLower.contains("\\.nvm\\")) {
            return "nvm";
        } else if (pathLower.contains("/homebrew/") || pathLower.contains("/cellar/")) {
            return "homebrew";
        } else if (pathLower.contains("/.volta/") || pathLower.contains("\\.volta\\")) {
            return "volta";
        } else if (pathLower.contains("fnm_multishells")) {
            return "fnm";
        } else if (pathLower.contains("/mise/") || pathLower.contains("\\mise\\")) {
            return "mise";
        } else if (pathLower.contains("/.bun/") || pathLower.contains("\\.bun\\")) {
            return "bun";
        } else if (pathLower.contains("/pnpm/") || pathLower.contains("\\pnpm\\")) {
            return "pnpm";
        } else if (pathLower.contains("/scoop/") || pathLower.contains("\\scoop\\")) {
            return "scoop";
        } else if (pathLower.contains("npm")) {
            return "npm";
        } else {
            return "system";
        }
    }

    /**
     * 获取CLI版本
     */
    private String getVersion(String cliPath) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath, "--version");
            pb.redirectErrorStream(true);
            process = pb.start();

            // 先等进程退出再读 stdout:readLine() 在 stalled 流上会永久阻塞,若置于 waitFor 之前
            // 则 10s 超时形同虚设(同 CommitMessageAiService 的反模式)。
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.readLine();
                if (output != null) {
                    Matcher matcher = VERSION_PATTERN.matcher(output);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Failed to get version for " + cliPath + ": " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        return null;
    }

    /**
     * 从npm registry获取最新版本
     */
    private String getLatestVersionFromNpm(String npmPackage) {
        Process process = null;
        try {
            // Windows 下 ProcessBuilder 不按 PATHEXT 解析,npm 实际入口是 npm.cmd
            // (与 findCliExecutable 的 .cmd 适配保持一致,否则 Windows 上永远抛 IOException → hasUpdate 恒 false)
            String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "view", npmPackage, "version");
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.readLine();
                if (output != null && !output.isEmpty()) {
                    Matcher matcher = VERSION_PATTERN.matcher(output);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Failed to get latest version from npm for " + npmPackage + ": " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        return null;
    }

    /**
     * 获取CLI工具的安装命令
     */
    public String getInstallCommand(String toolName) {
        for (CliToolDefinition tool : CLI_TOOLS) {
            if (tool.name.equals(toolName) && tool.npmPackage != null) {
                String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
                return npm + " install -g " + tool.npmPackage + "@latest";
            }
        }
        return null;
    }
    /**
     * npm 全局卸载超时(秒):只删文件不下载,120s 充裕(Windows 文件被占用时 npm 快速报错而非挂起)。
     */
    private static final long UNINSTALL_TIMEOUT_SECONDS = 120;

    /**
     * 后台线程持续读取进程合并输出(redirectErrorStream 后仅剩 stdout),防管道满阻塞。
     * 超过 {@link #INSTALL_OUTPUT_LIMIT_CHARS} 时丢弃头部保留尾部(失败原因多在末尾)。
     */
    private static StringBuilder captureProcessOutput(Process process) {
        StringBuilder output = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > INSTALL_OUTPUT_LIMIT_CHARS) {
                        output.delete(0, output.length() - INSTALL_OUTPUT_LIMIT_CHARS);
                    }
                }
            } catch (Exception ignored) {
                // Process may be terminating after timeout/cancel.
            }
        }, "cli-install-output-drain");
        drainer.setDaemon(true);
        drainer.start();
        return output;
    }

    private static String tailOutput(StringBuilder output) {
        String text = output.toString().trim();
        if (text.isEmpty()) {
            return "(无输出)";
        }
        return text.length() > 2000 ? "..." + text.substring(text.length() - 2000) : text;
    }

    /**
     * 安装/更新成功后失效全部 provider 的 CLI 探测缓存(成功路径与版本串一并清空,
     * 下轮 send 各自重新探测一次)。各清空入口均为幂等静态操作。
     */
    private static void invalidateResolverCaches() {
        try {
            ClaudeCliDetector.getInstance().clearCache();
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Failed to invalidate Claude detector cache: " + e.getMessage());
        }
        try {
            CodexCliResolver.clearCache();
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Failed to invalidate Codex resolver cache: " + e.getMessage());
        }
        try {
            ProviderCliResolver.clearAllCaches();
        } catch (Exception e) {
            LOG.warn("[CliEnvironmentChecker] Failed to invalidate provider resolver caches: " + e.getMessage());
        }
    }

    /**
     * 安装CLI工具
     */
    public CliEnvironmentStatus installCliTool(String toolName) {
        CliToolDefinition toolDef = null;
        for (CliToolDefinition tool : CLI_TOOLS) {
            if (tool.name.equals(toolName)) {
                toolDef = tool;
                break;
            }
        }

        if (toolDef == null) {
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(toolName, toolName, "", null);
            errorStatus.setError("未知的CLI工具: " + toolName);
            return errorStatus;
        }

        if (toolDef.npmPackage == null || toolDef.npmPackage.isBlank()) {
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
            );
            errorStatus.setError(toolDef.displayName + " 不支持通过npm安装");
            return errorStatus;
        }

        Process process = null;
        try {
            // 安装位置完全交由 npm 决定(与用户在终端执行同一条命令:同一 npm.cmd +
            // 同一用户级 ~/.npmrc,全局 prefix 天然一致),插件不传 --prefix 不改 npm config。
            String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "install", "-g", toolDef.npmPackage + "@latest");
            pb.redirectErrorStream(true);
            process = pb.start();

            // 全程 drain 合并流:npm 输出可撑满 OS 管道缓冲,写阻塞会让安装进程卡死
            // 表现为必超时;同时收集尾部输出供失败时透出原因。
            StringBuilder output = captureProcessOutput(process);

            boolean finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                // 杀整树:npm.cmd 在 Windows 是 cmd.exe 包装,destroyForcibly 只杀包装层,
                // node.exe 安装子进程会残留继续写全局 prefix(taskkill /F /T 语义)。
                PlatformUtils.terminateProcessAndWait(process, 10, TimeUnit.SECONDS);
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("安装超时(" + INSTALL_TIMEOUT_SECONDS + "秒),npm 输出:\n"
                        + tailOutput(output));
                return errorStatus;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("安装失败: " + tailOutput(output));
                return errorStatus;
            }

            // 安装/更新成功后失效各 provider 的 CLI 探测缓存:ClaudeCliDetector 的失败
            // 结果会被缓存,不清缓存则安装完成后使用侧仍报 "Claude CLI not found",
            // 直到重启 IDE;其余 resolver 的版本缓存同步刷新避免更新后读到旧版本。
            invalidateResolverCaches();

            // 安装成功，重新检测环境
            return checkCliEnvironment(toolDef);

        } catch (Exception e) {
            LOG.error("[CliEnvironmentChecker] Failed to install " + toolName, e);
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
            );
            errorStatus.setError("安装过程中出错: " + e.getMessage());
            return errorStatus;
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 卸载CLI工具(npm uninstall -g)。
     * 成功语义:npm exit 0 且重检 installed=false —— 此时"未找到CLI可执行文件"是预期
     * 终态而非失败,error 置空让 handler 以 success 下发;若 npm 报成功但重检仍 installed
     * (CLI 来自原生安装器/包管理器等非 npm 渠道),返回 error 提示手动卸载。
     */
    public CliEnvironmentStatus uninstallCliTool(String toolName) {
        CliToolDefinition toolDef = null;
        for (CliToolDefinition tool : CLI_TOOLS) {
            if (tool.name.equals(toolName)) {
                toolDef = tool;
                break;
            }
        }

        if (toolDef == null) {
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(toolName, toolName, "", null);
            errorStatus.setError("未知的CLI工具: " + toolName);
            return errorStatus;
        }

        if (toolDef.npmPackage == null || toolDef.npmPackage.isBlank()) {
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
            );
            errorStatus.setError(toolDef.displayName + " 不支持通过npm卸载");
            return errorStatus;
        }

        Process process = null;
        try {
            // 卸载与安装同源:同一 npm.cmd + 同一用户级 ~/.npmrc,npm 自行定位全局 prefix。
            String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "uninstall", "-g", toolDef.npmPackage);
            pb.redirectErrorStream(true);
            process = pb.start();

            // 全程 drain 合并输出(同 installCliTool:防管道满阻塞 + 收集失败原因)。
            StringBuilder output = captureProcessOutput(process);

            boolean finished = process.waitFor(UNINSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcessAndWait(process, 10, TimeUnit.SECONDS);
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                        toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("卸载超时(" + UNINSTALL_TIMEOUT_SECONDS + "秒),npm 输出:\n"
                        + tailOutput(output));
                return errorStatus;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                        toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("卸载失败: " + tailOutput(output));
                return errorStatus;
            }

            // 卸载成功,重检确认 CLI 已移除
            CliEnvironmentStatus status = checkCliEnvironment(toolDef);
            if (status.isInstalled()) {
                status.setError("卸载后仍检测到 " + toolDef.displayName
                        + "(可能为非 npm 安装,如原生安装器/包管理器),请手动卸载");
                return status;
            }
            // installed=false 是卸载的预期终态:清掉 checkCliEnvironment 写入的
            // "未找到CLI可执行文件",避免 handler 误判为失败而不下发状态刷新。
            status.setError(null);

            // 卸载成功后失效各 provider 的 CLI 探测缓存:成功缓存指向已删除的路径,
            // 不清缓存则下轮 send 会 spawn 一个不存在的可执行文件。
            invalidateResolverCaches();

            return status;

        } catch (Exception e) {
            LOG.error("[CliEnvironmentChecker] Failed to uninstall " + toolName, e);
            CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
            );
            errorStatus.setError("卸载过程中出错: " + e.getMessage());
            return errorStatus;
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * CLI工具定义
     */
    public static class CliToolDefinition {
        public final String name;
        public final String displayName;
        public final String description;
        public final String npmPackage;
        /** 主探测可执行名(默认与 name 相同);如 minimax 的官方 bin 是 mcode。 */
        public final String probeName;
        /** 备用可执行名(防御性回退),如 minimax 的 minimax;可为 null。 */
        public final String altName;

        public CliToolDefinition(String name, String displayName, String description, String npmPackage) {
            this(name, displayName, description, npmPackage, name, null);
        }

        public CliToolDefinition(String name, String displayName, String description,
                                 String npmPackage, String probeName, String altName) {
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.npmPackage = npmPackage;
            this.probeName = probeName;
            this.altName = altName;
        }
    }
}
