package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.cli.common.UserPathResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the Pi CLI executable.
 */
public final class PiCliResolver {

    private PiCliResolver() {
    }

    /**
     * 成功路径缓存。CLI 是 one-shot 架构(每轮 send 新建 session),{@code findExecutable()} 每轮都被调用,
     * 而内部 {@link #verify(String)} 会 spawn 'pi --version' 子进程,
     * 且 {@link #resolveNativeExecutable()} 最多调 2 次 verify(shim + 原生 .exe)。命中缓存后跳过全部
     * verify 流程,消除每轮 ~3-6s 的 pre-spawn 开销(对称 ClaudeCliDetector.cachedCliPath)。
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
            }
            return result;
        }
    }

    /** 测试钩子:直接注入缓存路径,跳过 verify(验证缓存命中语义)。 */
    static void __setCachedExecutableForTests(String path) {
        synchronized (CACHE_LOCK) {
            cachedExecutable = path;
        }
    }

    /** 测试钩子:清空缓存,强制下次 findExecutable 重新检测。 */
    static void __clearCacheForTests() {
        synchronized (CACHE_LOCK) {
            cachedExecutable = null;
            cachedVersion = null;
        }
    }

    /**
     * 返回缓存的 CLI 版本字符串,或 null(未检测 / 检测失败)。
     * 对称 ClaudeCliDetector.getCachedCliVersion()。
     */
    public static String getCachedVersion() {
        return cachedVersion;
    }

    private static String doFindExecutable() {
        // 优先:原生二进制(绕过 .cmd 批处理包装)
        String nativeExe = resolveNativeExecutable();
        if (nativeExe != null) {
            return nativeExe;
        }

        List<String> candidates = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            candidates.add(ProviderType.PI.cliCommandWindows());
            candidates.add("pi.exe");
            candidates.add("pi.bat");
        } else {
            candidates.add(ProviderType.PI.cliCommand());
        }

        for (String candidate : candidates) {
            String resolved = resolve(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return ProviderType.PI.cliCommandForPlatform();
    }

    /**
     * 从 pi shim 路径推断 npm 全局结构下的原生二进制入口
     * ({@code <shim-dir>/node_modules/pi/bin/pi.exe})。纯路径逻辑,不验证可执行性。
     *
     * @param shimPath pi shim(pi.cmd/pi)的绝对或相对路径
     * @return 原生 .exe 绝对路径;结构不存在或入参无效时返回 null
     */
    static String inferNativeExecutablePath(String shimPath) {
        if (shimPath == null || shimPath.isBlank()) {
            return null;
        }
        File shimDir = new File(shimPath).getAbsoluteFile().getParentFile();
        if (shimDir == null) {
            return null;
        }
        File nativeExe = new File(shimDir,
                "node_modules" + File.separator + "pi" + File.separator + "bin" + File.separator + "pi.exe");
        return nativeExe.exists() ? nativeExe.getAbsolutePath() : null;
    }

    /**
     * 解析 pi 原生二进制入口(仅 Windows),绕过 .cmd/.bat 批处理包装。直接 spawn 原生 .exe
     * 避免:① 多行 prompt 位置参数经 cmd.exe 包装被截断(--format json 等参数丢失 → exit0 无事件流);
     * ② stdin EOF 经 .cmd 包装传播不可靠(redirectInput(NUL) 在 .cmd 包装下仍可能不彻底)。
     */
    private static String resolveNativeExecutable() {
        if (!PlatformUtils.isWindows()) {
            return null;
        }
        String shim = resolve(ProviderType.PI.cliCommandWindows());
        if (shim == null) {
            shim = resolve("pi");
        }
        String inferred = inferNativeExecutablePath(shim);
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
        // 用 UserPathResolver 解析用户真实 PATH(IDE PATH + npm/scoop/volta 等 shim),
        // 修复 Windows 下经 npm 全局 / scoop / volta 装的 pi 在 IDE PATH 找不到 → fallback 裸名 → serve 启动失败。
        String pathEnv = UserPathResolver.resolveUserPath();
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }

        String[] suffixes = PlatformUtils.isWindows()
                ? new String[]{"", ".cmd", ".exe", ".bat"}
                : new String[]{""};

        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
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
        try {
            ProcessBuilder pb;
            String lower = path.toLowerCase();
            if (PlatformUtils.isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
                pb = new ProcessBuilder("cmd", "/c", "\"" + path + "\"", "--version");
            } else {
                pb = new ProcessBuilder(path, "--version");
            }
            Process process = pb.start();
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
                        .isVersionAccepted(ProviderType.PI, trimmed)) {
                    cachedVersion = trimmed;
                    return trimmed;
                }
                return null;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
