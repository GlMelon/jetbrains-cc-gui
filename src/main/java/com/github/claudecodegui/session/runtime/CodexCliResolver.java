package com.github.claudecodegui.session.runtime;

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
        }
    }

    private static String doFindExecutable() {
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

        for (String candidate : candidates) {
            String resolved = resolve(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return ProviderType.CODEX.cliCommandForPlatform();
    }

    /**
     * 从 codex shim 路径推断 npm 全局结构下的原生二进制入口
     * ({@code <shim-dir>/node_modules/@openai/codex/bin/codex.exe})。
     * Codex npm 包是 scoped package(@openai/codex),目录层级含 @openai/ 子目录。
     * 纯路径逻辑,不验证可执行性。
     *
     * @param shimPath codex shim(codex.cmd/codex)的绝对或相对路径
     * @return 原生 .exe 绝对路径;结构不存在或入参无效时返回 null
     */
    static String inferCodexNativeExecutablePath(String shimPath) {
        if (shimPath == null || shimPath.isBlank()) {
            return null;
        }
        File shimDir = new File(shimPath).getAbsoluteFile().getParentFile();
        if (shimDir == null) {
            return null;
        }
        File nativeExe = new File(shimDir,
                "node_modules" + File.separator + "@openai" + File.separator + "codex" + File.separator + "bin" + File.separator + "codex.exe");
        return nativeExe.exists() ? nativeExe.getAbsolutePath() : null;
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
        return verify(inferred) ? inferred : null;
    }

    static String resolve(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        File file = new File(candidate);
        if (file.isAbsolute() || candidate.contains(File.separator) || candidate.contains("/")) {
            return verify(file.getPath()) ? file.getPath() : null;
        }

        String found = searchInPath(candidate);
        if (found != null) {
            return found;
        }
        return null;
    }

    private static String searchInPath(String candidate) {
        String pathEnv = PlatformUtils.isWindows()
                ? PlatformUtils.getEnvIgnoreCase("PATH")
                : System.getenv("PATH");
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
                if (verify(file.getPath())) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean verify(String path) {
        try {
            ProcessBuilder pb;
            String lower = path.toLowerCase();
            if (PlatformUtils.isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
                pb = new ProcessBuilder("cmd", "/c", "\"" + path + "\"", "--version");
            } else {
                pb = new ProcessBuilder(path, "--version");
            }
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
