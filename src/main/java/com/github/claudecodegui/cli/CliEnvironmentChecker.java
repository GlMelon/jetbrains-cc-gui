package com.github.claudecodegui.cli;

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
    };

    /**
     * 版本号正则表达式
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:-[a-zA-Z0-9.]+)?)");

    // ── 检测结果缓存 ──────────────────────────────────────────────
    // 全量检测 = 6 个 CLI × (可执行文件探测 + --version + npm view),秒级耗时;
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
            // 1. 查找CLI可执行文件
            String cliPath = findCliExecutable(tool.name);
            
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
            // 当前 CLI_TOOLS 六家均有真实 npmPackage;本判空仅防御未来新增
            // 非 npm 分发条目(声明为 null/blank):npmPackage=null 会使
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
     * 查找CLI可执行文件
     */
    private String findCliExecutable(String cliName) {
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
            String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "install", "-g", toolDef.npmPackage + "@latest");
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("安装超时");
                return errorStatus;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                CliEnvironmentStatus errorStatus = new CliEnvironmentStatus(
                    toolDef.name, toolDef.displayName, toolDef.description, toolDef.npmPackage
                );
                errorStatus.setError("安装失败: " + output.toString().trim());
                return errorStatus;
            }

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
                process.destroyForcibly();
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

        public CliToolDefinition(String name, String displayName, String description, String npmPackage) {
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.npmPackage = npmPackage;
        }
    }
}
