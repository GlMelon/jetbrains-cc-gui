package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.UserPathResolver;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Detects and verifies the Claude CLI executable.
 *
 * <p>Registered as an application-level service via {@code @Service(Service.Level.APP)}.
 * The platform manages instantiation; callers resolve the singleton through {@link #getInstance()}.
 */
@Service(Service.Level.APP)
public class ClaudeCliDetector {

    private static final Logger LOG = Logger.getInstance(ClaudeCliDetector.class);

    private static final String[] WINDOWS_CLI_PATHS = {
            "%USERPROFILE%\\.claude\\local\\claude.exe",
            "%USERPROFILE%\\.claude\\local\\claude",
            "%LOCALAPPDATA%\\Programs\\claude\\claude.exe",
    };

    private static final String[] UNIX_CLI_PATHS = {
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
    };

    private volatile String cachedCliPath;
    private volatile String cachedCliVersion;

    /**
     * 负结果缓存时间戳(0 = 未探测过)。失败探测含多轮子进程 verify,未安装时每级都要
     * 起进程等待超时,缓存可避免每轮 send 重复全套探测;但**永久**负缓存会在用户安装
     * Claude CLI 后仍报 "Claude CLI not found" 直到重启 IDE(插件内一键安装由
     * CliEnvironmentChecker.invalidateResolverCaches 主动清缓存覆盖,终端手动安装
     * 由 TTL 过期自愈覆盖)。对称 CodexCliResolver/ProviderCliResolver 的
     * "never cache a failed probe" 语义,外加时限保护。
     */
    private static final long NEGATIVE_CACHE_TTL_MS = 60_000L;
    private volatile long detectionAttemptedAtMillis;

    /**
     * Public no-arg constructor: required for platform {@code applicationService} registration.
     */
    public ClaudeCliDetector() {
    }

    /**
     * Resolve the shared ClaudeCliDetector instance.
     * Prefers the platform-managed application service; falls back to a lazily created
     * instance for edge cases (early bootstrap / isolated unit tests).
     */
    public static ClaudeCliDetector getInstance() {
        try {
            ClaudeCliDetector service =
                    ApplicationManager.getApplication().getService(ClaudeCliDetector.class);
            if (service != null) {
                return service;
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager unavailable (isolated tests / plugin bootstrap).
        }
        return Holder.INSTANCE;
    }

    /**
     * Fallback instance for edge cases where the platform service is not resolvable.
     */
    private static final class Holder {
        private static final ClaudeCliDetector INSTANCE = new ClaudeCliDetector();

        private Holder() {
        }
    }

    /**
     * 返回缓存的 CLI 版本字符串,或 null(未检测 / 检测失败)。
     * 对称 CodexCliResolver.getCachedVersion() / OpenCodeCliResolver.getCachedVersion()。
     */
    public String getCachedCliVersion() {
        return cachedCliVersion;
    }

    public String findCliExecutable() {
        String cached = cachedCliPath;
        if (cached != null) {
            return cached;
        }
        if (isNegativeCacheFresh()) {
            return null;
        }
        synchronized (this) {
            cached = cachedCliPath;
            if (cached != null) {
                return cached;
            }
            if (isNegativeCacheFresh()) {
                return null;
            }
            String path = detectCliPath();
            detectionAttemptedAtMillis = System.currentTimeMillis();
            if (path != null) {
                cachedCliPath = path;
                LOG.info("[ClaudeCliDetector] Detected Claude CLI: " + path);
            } else {
                LOG.warn("[ClaudeCliDetector] Claude CLI executable not found");
            }
            return path;
        }
    }

    private boolean isNegativeCacheFresh() {
        long attemptedAt = detectionAttemptedAtMillis;
        return attemptedAt > 0
                && System.currentTimeMillis() - attemptedAt < NEGATIVE_CACHE_TTL_MS;
    }

    private String detectCliPath() {
        List<String> triedPaths = new ArrayList<>();

        String configuredPath = detectViaConfiguredPath(triedPaths);
        if (configuredPath != null) {
            return configuredPath;
        }

        String cmdResult = detectViaSystemCommand(triedPaths);
        if (cmdResult != null) {
            return cmdResult;
        }

        String knownPath = detectViaKnownPaths(triedPaths);
        if (knownPath != null) {
            return knownPath;
        }

        String pathResult = detectViaPathVariable(triedPaths);
        if (pathResult != null) {
            return pathResult;
        }

        String fallback = detectViaFallback(triedPaths);
        if (fallback != null) {
            return fallback;
        }

        LOG.info("[ClaudeCliDetector] Tried paths: " + triedPaths);
        return null;
    }

    private String detectViaConfiguredPath(List<String> triedPaths) {
        try {
            String configuredPath = CodemossSettingsService.getInstance().getClaudeCliPath();
            if (configuredPath == null || configuredPath.trim().isEmpty()) {
                return null;
            }

            String normalizedPath = configuredPath.trim();
            triedPaths.add(normalizedPath);
            String version = verifyCliPath(normalizedPath);
            if (version != null) {
                cachedCliVersion = version;
                LOG.info("[ClaudeCliDetector] Using configured Claude CLI path: " + normalizedPath + " (" + version + ")");
                return normalizedPath;
            }

            LOG.warn("[ClaudeCliDetector] Configured Claude CLI path is invalid: " + normalizedPath);
        } catch (Exception e) {
            LOG.debug("[ClaudeCliDetector] Failed to read configured Claude CLI path: " + e.getMessage());
        }
        return null;
    }

    private String detectViaSystemCommand(List<String> triedPaths) {
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        try {
            ProcessBuilder pb = PlatformUtils.isWindows()
                    ? new ProcessBuilder("where", "claude")
                    : new ProcessBuilder("which", "claude");
            // Windows 下注入用户真实 PATH(IDE PATH + npm/scoop/volta 等 shim 目录):
            // `where` 子进程默认继承 IDE 进程 PATH,经 npm 全局/包管理器安装的 claude
            // 不在其中时会漏检(对称 ProviderCliResolver.searchInPath 的解析方式)。
            if (PlatformUtils.isWindows()) {
                String userPath = UserPathResolver.resolveUserPath();
                if (userPath != null && !userPath.isBlank()) {
                    pb.environment().put("Path", userPath);
                }
            }
            process = pb.start();
            processManager = NodeService.getInstance().getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            drainQuietly(process.getErrorStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String path = reader.readLine();
                if (path != null && !path.isEmpty()) {
                    path = path.trim();
                    triedPaths.add(path);

                    if (PlatformUtils.isWindows()) {
                        String cmdPath = pickWindowsCmd(reader, path);
                        if (cmdPath != null) {
                            path = cmdPath;
                        }
                    }

                    String version = verifyCliPath(path);
                    if (version != null) {
                        cachedCliVersion = version;
                        LOG.info("[ClaudeCliDetector] Found Claude via where/which: " + path + " (" + version + ")");
                        return path;
                    }
                }
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            if (process != null) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("[ClaudeCliDetector] where/which lookup failed: " + e.getMessage());
        } finally {
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
        }
        return null;
    }

    private String pickWindowsCmd(BufferedReader reader, String firstPath) throws Exception {
        if (firstPath.endsWith(".cmd")) {
            return firstPath;
        }
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.endsWith(".cmd")) {
                return line;
            }
        }
        return null;
    }

    private String detectViaKnownPaths(List<String> triedPaths) {
        String userHome = PlatformUtils.getHomeDirectory();
        String[] templates = PlatformUtils.isWindows() ? WINDOWS_CLI_PATHS : UNIX_CLI_PATHS;

        List<String> pathsToCheck = new ArrayList<>();
        for (String template : templates) {
            pathsToCheck.add(expandEnvVars(template));
        }

        if (PlatformUtils.isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                pathsToCheck.add(appData + "\\npm\\claude.cmd");
                pathsToCheck.add(appData + "\\npm\\claude.exe");
            }
            // %LOCALAPPDATA%\npm:部分 node 发行版/用户级安装的 npm 默认全局目录
            // (对齐 CliEnvironmentChecker.getSearchDirectories 的 Windows 目录表)。
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                pathsToCheck.add(localAppData + "\\npm\\claude.cmd");
                pathsToCheck.add(localAppData + "\\npm\\claude.exe");
            }
            // npm 全局 prefix 反查(Windows 上全局 bin 即 prefix 根):覆盖用户 npmrc
            // 自定义 prefix 等上述固定目录表之外的位置。
            String npmGlobalPrefix = detectNpmGlobalPrefix();
            if (npmGlobalPrefix != null) {
                pathsToCheck.add(npmGlobalPrefix + "\\claude.cmd");
                pathsToCheck.add(npmGlobalPrefix + "\\claude.exe");
            }
        } else {
            pathsToCheck.add(userHome + "/.npm/bin/claude");
            String npmGlobalPrefix = detectNpmGlobalPrefix();
            if (npmGlobalPrefix != null) {
                pathsToCheck.add(npmGlobalPrefix + "/bin/claude");
            }
        }

        for (String path : pathsToCheck) {
            triedPaths.add(path);
            File file = new File(path);
            if (!file.exists()) {
                continue;
            }
            if (!PlatformUtils.isWindows() && !file.canExecute()) {
                continue;
            }
            String version = verifyCliPath(path);
            if (version != null) {
                cachedCliVersion = version;
                LOG.info("[ClaudeCliDetector] Found Claude in known path: " + path + " (" + version + ")");
                return path;
            }
        }
        return null;
    }

    private String detectViaPathVariable(List<String> triedPaths) {
        // 用 UserPathResolver 解析用户真实 PATH(Windows = IDE PATH + npm/scoop/volta 等
        // shim 目录;Unix 直接透传),对称 ProviderCliResolver.searchInPath。
        String pathEnv = UserPathResolver.resolveUserPath();
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        String cliName = ProviderType.CLAUDE.cliCommandForPlatform();
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir == null || dir.isEmpty()) {
                continue;
            }
            File cliFile = new File(dir, cliName);
            String absPath = cliFile.getAbsolutePath();
            triedPaths.add(absPath);
            if (!cliFile.exists()) {
                continue;
            }
            String version = verifyCliPath(absPath);
            if (version != null) {
                cachedCliVersion = version;
                LOG.info("[ClaudeCliDetector] Found Claude in PATH: " + absPath + " (" + version + ")");
                return absPath;
            }
        }
        return null;
    }

    private String detectViaFallback(List<String> triedPaths) {
        triedPaths.add("claude");
        String version = verifyCliPath("claude");
        if (version != null) {
            cachedCliVersion = version;
            LOG.info("[ClaudeCliDetector] Direct Claude invocation succeeded (" + version + ")");
            return "claude";
        }
        return null;
    }

    public String verifyCliPath(String path) {
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "--version");
            process = pb.start();
            processManager = NodeService.getInstance().getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            drainQuietly(process.getErrorStream());
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
                if (!trimmed.isEmpty() && CliCompatibilityService.getInstance()
                        .isVersionAccepted(ProviderType.CLAUDE, trimmed)) {
                    cachedCliVersion = trimmed;
                    return trimmed;
                }
            }
        } catch (InterruptedException e) {
            if (process != null) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("[ClaudeCliDetector] Verification failed [" + path + "]: " + e.getMessage());
        } finally {
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
        }
        return null;
    }

    /**
     * Sets the user-configured CLI path. Validates basic safety:
     * - must be absolute
     * - must point to a regular existing file (not a directory)
     * - on Unix must be executable
     * - file name must match the expected Claude CLI binary name
     * Invalid paths are rejected (cache stays unchanged + warning logged).
     * Pass null/blank to clear the cached path and force re-detection.
     * (PR #1191 review H2.)
     */
    public void setCliPath(String path) {
        synchronized (this) {
            if (path == null || path.isBlank()) {
                this.cachedCliPath = null;
                this.detectionAttemptedAtMillis = 0L;
                this.cachedCliVersion = null;
                return;
            }
            String trimmed = path.trim();
            if (!isValidCliPath(trimmed)) {
                LOG.warn("[ClaudeCliDetector] Rejected unsafe Claude CLI path: " + trimmed);
                return;
            }
            this.cachedCliPath = trimmed;
            this.detectionAttemptedAtMillis = System.currentTimeMillis();
            this.cachedCliVersion = verifyCliPath(trimmed);
        }
    }

    /**
     * Lightweight whitelist check for user-supplied CLI paths. Designed to stop the
     * settings UI from accidentally pointing the detector at an arbitrary binary
     * (e.g. /bin/sh) and then having verifyCliPath() execute it.
     */
    static boolean isValidCliPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File file = new File(path);
        if (!file.isAbsolute() || !file.isFile()) {
            return false;
        }
        if (!PlatformUtils.isWindows() && !file.canExecute()) {
            return false;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        // Accept "claude", "claude.cmd", "claude.exe", or "claude*.sh" style; reject everything else.
        return name.equals("claude")
                || name.equals("claude.cmd")
                || name.equals("claude.exe")
                || name.equals("claude.bat")
                || (name.startsWith("claude") && (name.endsWith(".cmd")
                        || name.endsWith(".exe")
                        || name.endsWith(".bat")
                        || name.endsWith(".sh")
                        || !name.contains(".")));
    }

    public String getCachedVersion() {
        return cachedCliVersion;
    }

    public void clearCache() {
        synchronized (this) {
            this.cachedCliPath = null;
            this.cachedCliVersion = null;
            this.detectionAttemptedAtMillis = 0L;
        }
    }

    private String expandEnvVars(String path) {
        if (path == null) {
            return null;
        }
        String result = path;
        result = result.replace("%USERPROFILE%", PlatformUtils.getHomeDirectory());
        result = result.replace("%APPDATA%", System.getenv("APPDATA") != null ? System.getenv("APPDATA") : "");
        result = result.replace("%LOCALAPPDATA%", System.getenv("LOCALAPPDATA") != null ? System.getenv("LOCALAPPDATA") : "");
        return result;
    }

    private String detectNpmGlobalPrefix() {
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        try {
            // Windows 下 ProcessBuilder 不按 PATHEXT 解析,npm 实际入口是 npm.cmd
            // (裸 "npm" 会 IOException 被 catch 吞掉,Windows 分支永远拿不到 prefix)。
            String npm = PlatformUtils.isWindows() ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "prefix", "-g");
            if (PlatformUtils.isWindows()) {
                String userPath = UserPathResolver.resolveUserPath();
                if (userPath != null && !userPath.isBlank()) {
                    pb.environment().put("Path", userPath);
                }
            }
            process = pb.start();
            processManager = NodeService.getInstance().getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            drainQuietly(process.getErrorStream());
            String prefix;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                prefix = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
                return null;
            }
            if (process.exitValue() == 0 && prefix != null && !prefix.isEmpty()) {
                return prefix.trim();
            }
        } catch (InterruptedException e) {
            if (process != null) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        } finally {
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
            }
        }
        return null;
    }

    private static void drainQuietly(java.io.InputStream inputStream) {
        Thread drainer = new Thread(() -> {
            try (java.io.InputStream input = inputStream) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception ignored) {
                // Best-effort drain for short-lived detector processes.
            }
        }, "claude-cli-detector-stderr-drain");
        drainer.setDaemon(true);
        drainer.start();
    }
}
