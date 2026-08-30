package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 参数化 CLI 可执行文件解析器(grok/kimi/pi/opencode 共用)。
 * <p>
 * 合并自原 GrokCliResolver/KimiCliResolver/PiCliResolver(三者归一化后仅 ProviderType 与
 * npm 目录名不同)。差异点全部参数化:
 * <ul>
 *   <li>{@code type.cliCommandWindows()/cliCommand()} —— 候选可执行名与 resolve 裸名;</li>
 *   <li>{@code npmDir} —— npm 全局结构下的包目录(grok/kimi/pi 为裸名,opencode 为 {@code "opencode-ai"})。</li>
 * </ul>
 * 缓存按 {@link ProviderType} 隔离(静态 map + per-type 锁),成功路径缓存、失败每轮重试,
 * 对称原各 Resolver 的 DCL 语义。{@code OpenCodeCliResolver} 以薄委托共享本类缓存
 * (BridgePreloader 预热与会话解析走同一份缓存)。
 */
public final class ProviderCliResolver {

    /** 成功路径缓存:CLI 是 one-shot 架构,findExecutable 每轮 send 都被调用,命中缓存跳过全部 verify。 */
    private static final Map<ProviderType, String> CACHED_EXECUTABLES = new ConcurrentHashMap<>();
    /** 缓存 CLI 版本字符串(对称 ClaudeCliDetector.cachedCliVersion)。 */
    private static final Map<ProviderType, String> CACHED_VERSIONS = new ConcurrentHashMap<>();

    private final ProviderType type;
    private final String npmDir;

    public ProviderCliResolver(ProviderType type, String npmDir) {
        this.type = type;
        this.npmDir = npmDir;
    }

    public String findExecutable() {
        String cached = CACHED_EXECUTABLES.get(type);
        if (cached != null) {
            return cached;
        }
        synchronized (type) {
            cached = CACHED_EXECUTABLES.get(type);
            if (cached != null) {
                return cached;
            }
            String result = doFindExecutable();
            if (result != null) {
                CACHED_EXECUTABLES.put(type, result);
                return result;
            }
            // Keep the bare command as a per-call fallback, but never cache a failed probe.
            return type.cliCommandForPlatform();
        }
    }

    /** 测试钩子:直接注入缓存路径,跳过 verify(验证缓存命中语义)。 */
    public static void __setCachedExecutableForTests(ProviderType type, String path) {
        synchronized (type) {
            CACHED_EXECUTABLES.put(type, path);
        }
    }

    /** 测试钩子:清空缓存,强制下次 findExecutable 重新检测。 */
    public static void __clearCacheForTests(ProviderType type) {
        synchronized (type) {
            CACHED_EXECUTABLES.remove(type);
            CACHED_VERSIONS.remove(type);
        }
    }

    /** 返回该 provider 缓存的 CLI 版本字符串,或 null(未检测 / 检测失败)。 */
    public static String getCachedVersion(ProviderType type) {
        return CACHED_VERSIONS.get(type);
    }

    private String doFindExecutable() {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        // 优先:原生二进制(绕过 .cmd 批处理包装)
        String nativeExe = resolveNativeExecutable();
        if (nativeExe != null) {
            return nativeExe;
        }

        List<String> candidates = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            candidates.add(type.cliCommandWindows());
            candidates.add(type.cliCommand() + ".exe");
            candidates.add(type.cliCommand() + ".bat");
        } else {
            candidates.add(type.cliCommand());
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
     * 从 CLI shim 路径推断 npm 全局结构下的原生二进制入口
     * ({@code <shim-dir>/node_modules/<npmDir>/bin/<exeName>.exe})。纯路径逻辑,不验证可执行性。
     *
     * @param shimPath shim(cliCommand.cmd / 裸名)的绝对或相对路径
     * @param npmDir   npm 包目录名(如 {@code grok} / {@code opencode-ai})
     * @param exeName  原生二进制名(= {@code cliCommand()},如 {@code grok} / {@code opencode})
     * @return 原生 .exe 绝对路径;结构不存在或入参无效时返回 null
     */
    public static String inferNativeExecutablePath(String shimPath, String npmDir, String exeName) {
        if (shimPath == null || shimPath.isBlank()) {
            return null;
        }
        File shimDir = new File(shimPath).getAbsoluteFile().getParentFile();
        if (shimDir == null) {
            return null;
        }
        File nativeExe = new File(shimDir,
                "node_modules" + File.separator + npmDir + File.separator + "bin" + File.separator + exeName + ".exe");
        return nativeExe.exists() ? nativeExe.getAbsolutePath() : null;
    }

    /**
     * 解析原生二进制入口(仅 Windows),绕过 .cmd/.bat 批处理包装。直接 spawn 原生 .exe
     * 避免:① 多行 prompt 位置参数经 cmd.exe 包装被截断(--format json 等参数丢失 → exit0 无事件流);
     * ② stdin EOF 经 .cmd 包装传播不可靠(redirectInput(NUL) 在 .cmd 包装下仍可能不彻底)。
     */
    private String resolveNativeExecutable() {
        if (!PlatformUtils.isWindows()) {
            return null;
        }
        String shim = resolve(type.cliCommandWindows());
        if (shim == null) {
            shim = resolve(type.cliCommand());
        }
        String inferred = inferNativeExecutablePath(shim, npmDir, type.cliCommand());
        if (inferred == null) {
            return null;
        }
        return verify(inferred) != null ? inferred : null;
    }

    String resolve(String candidate) {
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

    private String searchInPath(String candidate) {
        // 用 UserPathResolver 解析用户真实 PATH(IDE PATH + npm/scoop/volta 等 shim),
        // 修复 Windows 下经 npm 全局 / scoop / volta 装的 CLI 在 IDE PATH 找不到 → fallback 裸名 → 启动失败。
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
     */
    private String verify(String path) {
        Process process = null;
        try {
            ProcessBuilder pb;
            String lower = path.toLowerCase();
            if (PlatformUtils.isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
                pb = new ProcessBuilder("cmd", "/c", "\"" + path + "\"", "--version");
            } else {
                pb = new ProcessBuilder(path, "--version");
            }
            process = pb.start();
            String version;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                version = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() == 0 && version != null) {
                String trimmed = version.trim();
                if (!trimmed.isEmpty() && CliCompatibilityService.getInstance()
                        .isVersionAccepted(type, trimmed)) {
                    CACHED_VERSIONS.put(type, trimmed);
                    return trimmed;
                }
                return null;
            }
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
