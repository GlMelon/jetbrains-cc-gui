package com.github.claudecodegui.cli.opencode;

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
 * Resolves the OpenCode CLI executable.
 */
public final class OpenCodeCliResolver {

    private OpenCodeCliResolver() {
    }

    public static String findExecutable() {
        // 优先:原生二进制(绕过 .cmd 批处理包装,修复多行 prompt 位置参数被 cmd.exe 截断
        // + stdin EOF 经 .cmd 包装传播不可靠 → opencode exit0 无事件流)
        String nativeExe = resolveNativeExecutable();
        if (nativeExe != null) {
            return nativeExe;
        }

        List<String> candidates = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            candidates.add(ProviderType.OPENCODE.cliCommandWindows());
            candidates.add("opencode.exe");
            candidates.add("opencode.bat");
        } else {
            candidates.add(ProviderType.OPENCODE.cliCommand());
        }

        for (String candidate : candidates) {
            String resolved = resolve(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return ProviderType.OPENCODE.cliCommandForPlatform();
    }

    /**
     * 从 opencode shim 路径推断 npm 全局结构下的原生二进制入口
     * ({@code <shim-dir>/node_modules/opencode-ai/bin/opencode.exe})。纯路径逻辑,不验证可执行性。
     *
     * @param shimPath opencode shim(opencode.cmd/opencode)的绝对或相对路径
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
                "node_modules" + File.separator + "opencode-ai" + File.separator + "bin" + File.separator + "opencode.exe");
        return nativeExe.exists() ? nativeExe.getAbsolutePath() : null;
    }

    /**
     * 解析 opencode 原生二进制入口(仅 Windows),绕过 .cmd/.bat 批处理包装。直接 spawn 原生 .exe
     * 避免:① 多行 prompt 位置参数经 cmd.exe 包装被截断(--format json 等参数丢失 → exit0 无事件流);
     * ② stdin EOF 经 .cmd 包装传播不可靠(redirectInput(NUL) 在 .cmd 包装下仍可能不彻底)。
     */
    private static String resolveNativeExecutable() {
        if (!PlatformUtils.isWindows()) {
            return null;
        }
        String shim = resolve(ProviderType.OPENCODE.cliCommandWindows());
        if (shim == null) {
            shim = resolve("opencode");
        }
        String inferred = inferNativeExecutablePath(shim);
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
        // 用 UserPathResolver 解析用户真实 PATH(IDE PATH + npm/scoop/volta 等 shim),
        // 修复 Windows 下经 npm 全局 / scoop / volta 装的 opencode 在 IDE PATH 找不到 → fallback 裸名 → serve 启动失败。
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
