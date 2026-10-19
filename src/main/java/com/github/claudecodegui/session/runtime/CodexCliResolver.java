package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.UserPathResolver;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.cli.compatibility.CodexCliVersionParser;
import com.github.claudecodegui.util.PlatformUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the Codex CLI executable.
 */
public final class CodexCliResolver {

    private CodexCliResolver() {
    }

    /**
     * 成功路径缓存。CLI 是 one-shot 架构(每轮 send 新建 session),{@code findExecutable()} 每轮都被调用,
     * 而内部 {@link #verify(String)} 会 spawn 'codex --version' 子进程(Node .cmd 包装冷启动 ~3s)。
     * 命中缓存后跳过整个 verify 流程,消除每轮 ~3s 的 pre-spawn 开销(对称 ClaudeCliDetector.cachedCliPath)。
     * 只缓存成功路径(不缓存失败):本 resolver 无用户配置入口来打破"首次时序致永久失败"僵局,
     * 故保守地让未安装场景每轮重试(失败本就会快速报错,无需优化)。
     */
    private static volatile String cachedExecutable;
    private static final Object CACHE_LOCK = new Object();

    /** 缓存 CLI 版本字符串(对称 ClaudeCliDetector.cachedCliVersion)。 */
    private static volatile String cachedVersion;

    public static String findExecutable() {
        String cached = cachedExecutable;
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            if (cachedExecutable != null) {
                return cachedExecutable;
            }
            String result = doFindExecutable();
            if (result != null) {
                cachedExecutable = result;
                return result;
            }
            // Keep the bare command as a per-call fallback, but never cache a failed probe.
            return ProviderType.CODEX.cliCommandForPlatform();
        }
    }

    /** 测试钩子:直接注入缓存路径,跳过 verify(验证缓存命中语义)。 */
    static void __setCachedExecutableForTests(String path) {
        synchronized (CACHE_LOCK) {
            cachedExecutable = path;
        }
    }

    /**
     * 清空解析缓存(成功路径 + 版本串),强制下次 findExecutable 重新探测。
     * 供 CLI 安装/更新成功后失效旧缓存调用(CliEnvironmentChecker.invalidateResolverCaches)。
     */
    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            cachedExecutable = null;
            cachedVersion = null;
        }
    }

    /**
     * 测试钩子:清空缓存,强制下次 findExecutable 重新检测。
     */
    static void __clearCacheForTests() {
        clearCache();
    }

    /**
     * 返回缓存的 CLI 版本字符串,或 null(未检测 / 检测失败)。
     * 对称 ClaudeCliDetector.getCachedCliVersion()。
     */
    public static String getCachedVersion() {
        return cachedVersion;
    }

    private static String doFindExecutable() {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        // 优先:原生二进制(绕过 .cmd 批处理包装,消除 cmd /c 的参数截断与 stdin 不可靠传播)
        String nativeExe = resolveNativeExecutable();
        if (nativeExe != null) {
            return nativeExe;
        }

        List<String> candidates = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            candidates.add(ProviderType.CODEX.cliCommandWindows());
            candidates.add("codex.exe");
            candidates.add("codex.bat");
        } else {
            candidates.add(ProviderType.CODEX.cliCommand());
        }

        // 官方独立安装器布局(对齐 upstream fe95dd8e):这些目录不在 IDE 进程 PATH 上,
        // 独立安装的 codex 只能按布局显式发现;优先于 PATH shim(半安装 shim 可能仍可执行)。
        for (String dir : standaloneBinDirs()) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            for (String candidate : candidates) {
                File file = new File(dir, candidate);
                if (file.isFile() && verify(file.getPath()) != null) {
                    return file.getAbsolutePath();
                }
            }
        }

        for (String candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            String resolved = resolve(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    /**
     * 官方独立安装器布局候选目录(对齐 upstream fe95dd8e 的 codexHomeBinDirs):
     * {@code CODEX_INSTALL_DIR} 覆盖、Windows
     * {@code %LOCALAPPDATA%\Programs\OpenAI\Codex\bin}、
     * {@code ${CODEX_HOME:-~/.codex}/packages/standalone/current} 的 bin/ 与扁平两种布局、
     * 以及 {@code ~/.local/bin}/{@code ~/.cargo/bin}/{@code ~/.codex/bin}。
     * 纯函数(无 PATH 依赖),便于单测。
     */
    static List<String> standaloneBinDirs(
            String home, String localAppData, String codexInstallDir, String codexHome) {
        List<String> dirs = new ArrayList<>();
        if (codexInstallDir != null && !codexInstallDir.isBlank()) {
            dirs.add(codexInstallDir.trim());
        }
        if (PlatformUtils.isWindows()) {
            String base = localAppData != null && !localAppData.isBlank()
                    ? localAppData
                    : (home != null && !home.isBlank() ? home + File.separator + "AppData" + File.separator + "Local" : null);
            if (base != null) {
                dirs.add(base + File.separator + "Programs" + File.separator + "OpenAI"
                        + File.separator + "Codex" + File.separator + "bin");
            }
        }
        if (home != null && !home.isBlank()) {
            dirs.add(home + File.separator + ".local" + File.separator + "bin");
            String current = (codexHome != null && !codexHome.isBlank() ? codexHome.trim()
                    : home + File.separator + ".codex")
                    + File.separator + "packages" + File.separator + "standalone" + File.separator + "current";
            // 只跟随安装器选中的版本;旧版独立包把二进制直接放在 current 下(扁平布局)。
            dirs.add(current + File.separator + "bin");
            dirs.add(current);
            dirs.add(home + File.separator + ".cargo" + File.separator + "bin");
            dirs.add(home + File.separator + ".codex" + File.separator + "bin");
        }
        return dirs;
    }

    private static List<String> standaloneBinDirs() {
        return standaloneBinDirs(
                PlatformUtils.getHomeDirectory(),
                PlatformUtils.getEnvIgnoreCase("LOCALAPPDATA"),
                PlatformUtils.getEnvIgnoreCase("CODEX_INSTALL_DIR"),
                PlatformUtils.getEnvIgnoreCase("CODEX_HOME"));
    }

    /**
     * 从 codex shim 路径推断 npm 全局结构下的原生二进制入口。Codex npm 包是 scoped package
     * (@openai/codex),目录层级含 @openai/ 子目录。覆盖两种 npm 布局:
     * <ol>
     *   <li><b>扁平</b>(优先):{@code <shim-dir>/node_modules/@openai/codex/bin/codex.exe}</li>
     *   <li><b>nested</b>(回退,codex 0.142.5+):平台子包经其自身 node_modules 安装——
     *       {@code <shim-dir>/node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe}</li>
     * </ol>
     * 纯路径逻辑,不验证可执行性(由 {@link #resolveNativeExecutable} 统一 verify)。
     *
     * <p>nested 布局关键:gateway 重构后 {@code -c mcp_servers.*.args=[...]} 经原生 codex.exe argv 直传
     * 才可靠(见 docs/codex-gateway-config-injection-refactor.md §5),必须能定位到 .exe。
     *
     * @param shimPath codex shim(codex.cmd/codex)的绝对或相对路径
     * @return 原生 .exe 绝对路径;两种结构都不存在或入参无效时返回 null
     */
    static String inferCodexNativeExecutablePath(String shimPath) {
        if (shimPath == null || shimPath.isBlank()) {
            return null;
        }
        File shimDir = new File(shimPath).getAbsoluteFile().getParentFile();
        if (shimDir == null) {
            return null;
        }
        // 布局一(扁平,优先)
        File flat = new File(shimDir,
                "node_modules" + File.separator + "@openai" + File.separator + "codex" + File.separator + "bin" + File.separator + "codex.exe");
        if (flat.exists()) {
            return flat.getAbsolutePath();
        }
        // 布局二(nested npm scoped + 平台子包,codex 0.142.5+)
        File nested = new File(shimDir,
                "node_modules" + File.separator + "@openai" + File.separator + "codex"
                        + File.separator + "node_modules" + File.separator + "@openai"
                        + File.separator + "codex-win32-x64" + File.separator + "vendor"
                        + File.separator + "x86_64-pc-windows-msvc" + File.separator + "bin"
                        + File.separator + "codex.exe");
        return nested.exists() ? nested.getAbsolutePath() : null;
    }

    /**
     * 解析 codex 原生二进制入口(仅 Windows),绕过 .cmd/.bat 批处理包装。直接 spawn 原生 .exe
     * 避免 cmd /c 环境下多行 prompt 位置参数被截断(--format json 等参数丢失)。
     * 对称 OpenCodeCliResolver.resolveNativeExecutable()。
     */
    private static String resolveNativeExecutable() {
        if (!PlatformUtils.isWindows()) {
            return null;
        }
        String shim = resolve(ProviderType.CODEX.cliCommandWindows());
        if (shim == null) {
            shim = resolve("codex");
        }
        String inferred = inferCodexNativeExecutablePath(shim);
        if (inferred == null) {
            return null;
        }
        return verify(inferred) != null ? inferred : null;
    }

    static String resolve(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        File file = new File(candidate);
        if (file.isAbsolute() || candidate.contains(File.separator) || candidate.contains("/")) {
            return verify(file.getPath()) != null ? file.getPath() : null;
        }

        String found = searchInPath(candidate);
        if (found != null) {
            return found;
        }
        return null;
    }

    private static String searchInPath(String candidate) {
        // 用 UserPathResolver 解析用户真实 PATH(对称 ProviderCliResolver.searchInPath):
        // IDE 进程 PATH 常缺失 npm/scoop/volta 等 shim 目录,独立安装的 codex 因此漏检;
        // IDE PATH 保持在前,不破坏本地「CLI PATH 优先」的既有决策(9d4397d4)。
        String pathEnv = UserPathResolver.resolveUserPath();
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }

        String[] suffixes = PlatformUtils.isWindows()
                ? new String[]{"", ".cmd", ".exe", ".bat"}
                : new String[]{""};

        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                File file = new File(dir, candidate + suffix);
                if (verify(file.getPath()) != null) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * 验证 CLI 可执行性并捕获版本字符串。
     * 对称 ClaudeCliDetector.verifyCliPath: 返回 stdout 首行版本串,或 null(失败)。
     * 版本缓存经 {@link #getCachedVersion()} 读取。
     */
    private static String verify(String path) {
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        Thread stderrDrain = null;
        try {
            ProcessBuilder pb;
            String lower = path.toLowerCase();
            if (PlatformUtils.isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
                pb = new ProcessBuilder("cmd", "/c", "\"" + path + "\"", "--version");
            } else {
                pb = new ProcessBuilder(path, "--version");
            }
            process = pb.start();
            processManager = NodeService.getInstance().getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            if (processToken == null) {
                return null;
            }
            stderrDrain = startErrorDrain(process.getErrorStream());
            String version;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                version = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
                return null;
            }
            if (process.exitValue() == 0 && version != null) {
                String trimmed = version.trim();
                // 探测门控(对齐 upstream fe95dd8e):半安装的 npm launcher 仍可执行(exit 0),
                // 只有真实 `codex-cli <version>` 输出才能证明 CLI 可启动。
                if (!trimmed.isEmpty()
                        && CodexCliVersionParser.STRICT_VERSION_LINE.matcher(trimmed).find()
                        && CliCompatibilityService.getInstance()
                                .isVersionAccepted(ProviderType.CODEX, trimmed)) {
                    cachedVersion = trimmed;
                    return trimmed;
                }
                return null;
            }
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
            // 项7:waitFor 抛 InterruptedException,原被 catch(Exception ignored) 吞掉丢失中断标志——
            // 上层无法感知中断。恢复中断标志后返回 null(版本检测中止)。
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (stderrDrain != null) {
                stderrDrain.interrupt();
            }
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
        }
    }

    private static Thread startErrorDrain(java.io.InputStream inputStream) {
        Thread thread = new Thread(() -> {
            try (java.io.InputStream input = inputStream) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) {
                    // Version probing only needs stdout; stderr must still be drained to avoid pipe backpressure.
                }
            } catch (Exception ignored) {
                // The process may be terminating while the probe is being cleaned up.
            }
        }, "AICG-CLI-Version-Stderr-Drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
