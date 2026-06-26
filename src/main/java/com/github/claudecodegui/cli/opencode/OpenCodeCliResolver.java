package com.github.claudecodegui.cli.opencode;

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
