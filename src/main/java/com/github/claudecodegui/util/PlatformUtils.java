package com.github.claudecodegui.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Platform utility class.
 * Provides cross-platform compatibility support including platform detection,
 * environment variable handling, and process management.
 */
public class PlatformUtils {

    private static final Logger LOG = Logger.getInstance(PlatformUtils.class);

    // Platform type cache
    private static volatile PlatformType cachedPlatformType = null;
    // Plugin ID cache
    private static volatile String cachedPluginId = null;
    // Dev mode cache: null = not initialized, Boolean = cached result
    private static volatile Boolean cachedDevMode = null;
    // Real OS home directory cache
    private static volatile String cachedRealHomeDir = null;
    // Temp directory cache
    private static volatile String cachedTempDir = null;

    /**
     * Platform type enumeration.
     */
    public enum PlatformType {
        WINDOWS,
        MACOS,
        LINUX,
        UNKNOWN
    }

    // ==================== Platform Detection ====================

    /**
     * Get the current platform type.
     *
     * @return the platform type enum value
     */
    public static PlatformType getPlatformType() {
        if (cachedPlatformType == null) {
            // Delegate to the platform's SystemInfo: maintained detection logic
            // (handles os.name variants we would otherwise guess at).
            if (SystemInfo.isWindows) {
                cachedPlatformType = PlatformType.WINDOWS;
            } else if (SystemInfo.isMac) {
                cachedPlatformType = PlatformType.MACOS;
            } else if (SystemInfo.isLinux) {
                cachedPlatformType = PlatformType.LINUX;
            } else {
                cachedPlatformType = PlatformType.UNKNOWN;
            }
        }
        return cachedPlatformType;
    }

    /**
     * Check whether the current platform is Windows.
     *
     * @return true if running on Windows
     */
    public static boolean isWindows() {
        return getPlatformType() == PlatformType.WINDOWS;
    }

    /**
     * Check whether the current platform is macOS.
     *
     * @return true if running on macOS
     */
    public static boolean isMac() {
        return getPlatformType() == PlatformType.MACOS;
    }

    /**
     * Get the current plugin ID.
     * Automatically detects the ID by iterating over all plugins and matching the classloader,
     * avoiding hardcoded values.
     *
     * @return the plugin ID, or a fallback value if detection fails
     */
    public static String getPluginId() {
        if (cachedPluginId == null) {
            synchronized (PlatformUtils.class) {
                if (cachedPluginId == null) {
                    try {
                        cachedPluginId = PluginMetadata.getPluginId();
                        LOG.info("Plugin ID detected: " + cachedPluginId);
                    } catch (Exception e) {
                        LOG.warn("Failed to detect plugin ID: " + e.getMessage());
                        cachedPluginId = PluginMetadata.getPluginId();
                    }
                }
            }
        }
        return cachedPluginId;
    }

    /**
     * Check if the plugin is running in development mode.
     * Detection is based on multiple indicators: IDE Internal Mode, debugger attachment,
     * sandbox paths, build directories, etc.
     * <p>
     * The result is cached on first call to avoid repeated checks.
     * Uses double-checked locking for thread safety.
     *
     * @return true if running in development mode
     */
    public static boolean isPluginDevMode() {
        // Fast path: return cached value if already computed
        if (cachedDevMode != null) {
            return cachedDevMode;
        }

        // Double-checked locking to ensure single computation
        synchronized (PlatformUtils.class) {
            if (cachedDevMode == null) {
                cachedDevMode = computeDevMode();
            }
        }
        return cachedDevMode;
    }

    /**
     * Compute whether the plugin is running in development mode.
     * This method performs the actual detection logic.
     *
     * @return true if running in development mode
     */
    private static boolean computeDevMode() {
        try {
            // Check if IDE is running in Internal Mode
            var app = ApplicationManager.getApplication();
            if (app != null && app.isInternal()) {
                LOG.info("Dev mode detected: IDE Internal Mode enabled");
                return true;
            }

            // Check if a debugger is attached to the JVM
            if (isDebuggerAttached()) {
                LOG.info("Dev mode detected: debugger attached");
                return true;
            }

            // Check if system path contains sandbox (typical for runIde)
            String systemPath = System.getProperty("idea.system.path");
            if (systemPath != null && systemPath.contains("sandbox")) {
                LOG.info("Dev mode detected: sandbox system path");
                return true;
            }

            // Check if plugins path contains build
            String pluginsPath = System.getProperty("idea.plugins.path");
            if (pluginsPath != null && pluginsPath.contains("build")) {
                LOG.info("Dev mode detected: build plugins path");
                return true;
            }

            // Check plugin actual path using the current classpath location.
            File pluginDir = PluginMetadata.getPluginDirectory(PlatformUtils.class);
            if (pluginDir != null) {
                String pluginPath = pluginDir.getAbsolutePath();
                if (pluginPath.contains("build")) {
                    LOG.info("Dev mode detected: plugin path contains build");
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to detect plugin dev mode: " + e.getMessage());
        }

        LOG.info("Dev mode not detected, running in production mode");
        return false;
    }

    /**
     * Check if a debugger is attached to the JVM.
     * Detection is based on common debug agent flags in JVM input arguments.
     *
     * @return true if debugger is detected
     */
    private static boolean isDebuggerAttached() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                           .anyMatch(arg -> arg.contains("-agentlib:jdwp") ||
                                                    arg.contains("-Xdebug") ||
                                                    arg.contains("-Xrunjdwp"));
        } catch (Exception e) {
            LOG.warn("Failed to check debugger attachment: " + e.getMessage());
        }
        return false;
    }

    // ==================== Environment Variable Handling ====================

    /**
     * Get an environment variable with case-insensitive lookup (for Windows compatibility).
     * Windows environment variable names are case-insensitive, but Java's System.getenv()
     * returns a case-sensitive Map.
     *
     * @param name the environment variable name
     * @return the environment variable value, or null if not found
     */
    public static String getEnvIgnoreCase(String name) {
        if (name == null) {
            return null;
        }

        // Try exact match first
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }

        // On Windows, perform case-insensitive search
        if (isWindows()) {
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    /**
     * Get the PATH environment variable (handles both "Path" and "PATH" on Windows).
     *
     * @return the PATH environment variable value
     */
    public static String getPathEnv() {
        return getEnvIgnoreCase("PATH");
    }

    // ==================== File Operations ====================

    /**
     * Delete a file with retry logic (handles Windows file locking issues).
     *
     * @param file       the file to delete
     * @param maxRetries maximum number of retry attempts
     * @return true if deletion succeeded
     */
    public static boolean deleteWithRetry(File file, int maxRetries) {
        if (file == null || !file.exists()) {
            return true;
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (file.delete()) {
                return true;
            }

            if (attempt < maxRetries - 1) {
                try {
                    // Exponential backoff: 200ms, 400ms, 800ms
                    long waitTime = 200L * (1L << attempt);
                    Thread.sleep(waitTime);
                    // Hint the GC, which may release file handles
                    System.gc();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOG.warn("Failed to delete file (possibly locked): " + file.getAbsolutePath());
        return false;
    }

    /**
     * Recursively delete a directory with retry logic.
     *
     * @param directory  the directory to delete
     * @param maxRetries maximum number of retry attempts
     * @return true if deletion succeeded
     */
    public static boolean deleteDirectoryWithRetry(File directory, int maxRetries) {
        if (directory == null || !directory.exists()) {
            return true;
        }

        if (directory.isFile()) {
            return deleteWithRetry(directory, maxRetries);
        }

        // Recursively delete child files and subdirectories
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!deleteDirectoryWithRetry(file, maxRetries)) {
                    return false;
                }
            }
        }

        // Delete the now-empty directory
        return deleteWithRetry(directory, maxRetries);
    }

    // ==================== Process Management ====================

    /**
     * Terminate a process tree (including all child processes).
     * On Windows, uses taskkill /F /T /PID. On Unix, uses the standard destroy/destroyForcibly.
     *
     * @param process the process to terminate
     */
    public static void terminateProcess(Process process) {
        if (process == null) {
            return;
        }
        long pid = process.pid();
        // Windows:无论父进程死活,先按 ParentProcessId 清理子进程。taskkill /T 在进程树断裂
        // (cmd.exe/.cmd shim 中间层提前退出、或父进程已崩溃如 MCP gateway 自愈)时漏杀子进程;
        // Windows 子进程的 ParentProcessId 在父死后仍保留原值,故父已死也能查到孤儿子进程。
        if (isWindows()) {
            cleanupChildProcesses(pid);
        }
        if (!process.isAlive()) {
            return;
        }

        try {
            if (isWindows()) {
                // Use taskkill to terminate the process tree
                // /F = force termination
                // /T = terminate the entire process tree (including children)
                ProcessBuilder pb = new ProcessBuilder(
                        "taskkill", "/F", "/T", "/PID", String.valueOf(pid)
                );
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process killer = pb.start();
                boolean finished = killer.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    killer.destroyForcibly();
                }
                if ((!finished || killer.exitValue() != 0) && process.isAlive()) {
                    // taskkill may be blocked by the host policy even for a child we own.
                    // Fall back to the Process API so lifecycle cleanup still terminates
                    // the directly tracked process instead of silently reporting success.
                    process.destroyForcibly();
                }
            } else {
                ProcessHandle handle = process.toHandle();
                List<ProcessHandle> descendants = new ArrayList<>();
                try {
                    handle.descendants().forEach(descendants::add);
                } catch (Exception ignored) {
                }

                for (ProcessHandle child : descendants) {
                    try {
                        child.destroy();
                    } catch (Exception ignored) {
                    }
                }

                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    for (ProcessHandle child : descendants) {
                        try {
                            if (child.isAlive()) {
                                child.destroyForcibly();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            try {
                try {
                    ProcessHandle handle = process.toHandle();
                    List<ProcessHandle> descendants = new ArrayList<>();
                    try {
                        handle.descendants().forEach(descendants::add);
                    } catch (Exception ignored) {
                    }
                    for (ProcessHandle child : descendants) {
                        try {
                            child.destroyForcibly();
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Terminate a process tree and wait for it to exit.
     * Uses the platform-aware tree termination logic and then waits for the
     * requested timeout to reduce duplicated shutdown code at call sites.
     *
     * @param process the process to terminate
     * @param timeout the wait timeout
     * @param unit the timeout unit
     * @return true if the process is no longer alive after waiting
     */
    public static boolean terminateProcessAndWait(Process process, long timeout, TimeUnit unit) {
        if (process == null) {
            return true;
        }
        if (!process.isAlive()) {
            return true;
        }

        terminateProcess(process);

        try {
            if (process.waitFor(timeout, unit)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }

        return !process.isAlive();
    }

    /**
     * Terminate a process tree by PID.
     *
     * @param pid the process ID
     * @return true if the termination command executed successfully
     */
    public static boolean terminateProcessTree(long pid) {
        try {
            if (isWindows()) {
                ProcessBuilder pb = new ProcessBuilder(
                        "taskkill", "/F", "/T", "/PID", String.valueOf(pid)
                );
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process killer = pb.start();
                boolean result = killer.waitFor(5, TimeUnit.SECONDS);

                // Clean up orphaned conhost.exe processes after terminating the parent
                cleanupOrphanedConhosts(pid);

                return result;
            } else {
                // Unix: try using the kill command
                ProcessBuilder pb = new ProcessBuilder(
                        "kill", "-9", String.valueOf(pid)
                );
                pb.redirectErrorStream(true);
                Process killer = pb.start();
                return killer.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOG.warn("Failed to terminate process (PID: " + pid + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Clean up orphaned conhost.exe processes that were children of a terminated process.
     * This is a Windows-specific issue where conhost.exe processes may not be automatically
     * terminated when their parent process is killed.
     *
     * @param parentPid the PID of the parent process that was just terminated
     */
    public static void cleanupOrphanedConhosts(long parentPid) {
        if (!isWindows()) {
            return;
        }

        try {
            List<Long> conhostPids = queryProcessPids("name='conhost.exe' and ParentProcessId=" + parentPid);
            int cleanedCount = 0;

            for (long conhostPid : conhostPids) {
                LOG.info("[ProcessCleanup] Terminating orphaned conhost.exe (PID: " + conhostPid +
                        ", parent: " + parentPid + ")");

                ProcessBuilder taskkillBuilder = new ProcessBuilder(
                        "taskkill", "/F", "/PID", String.valueOf(conhostPid)
                );
                taskkillBuilder.redirectErrorStream(true);
                Process taskkillProcess = taskkillBuilder.start();
                taskkillProcess.waitFor(5, TimeUnit.SECONDS);
                cleanedCount++;
            }

            if (cleanedCount > 0) {
                LOG.info("[ProcessCleanup] Cleaned up " + cleanedCount + " orphaned conhost.exe process(es)");
            }
        } catch (Exception e) {
            LOG.debug("[ProcessCleanup] Failed to clean up conhost.exe processes: " + e.getMessage());
        }
    }

    /**
     * 递归终止某 PID 的所有(任意名字的)子进程及后代。
     *
     * <p>Windows 子进程的 {@code ParentProcessId} 在父进程死后<b>仍保留原值</b>(Windows 没有 Unix 那样的
     * reparent),故即便父进程已退出,也能按 {@code ParentProcessId} 查到它遗留的孤儿子进程——这正是
     * {@code taskkill /F /T} 在进程树断裂时漏杀的场景(① {@code .cmd} shim 的 {@code cmd.exe} 中间层提前
     * 退出;② 父进程先于 Java terminate 崩溃,如 MCP gateway 自愈重启留下的 MCP server 孤儿)。
     *
     * <p>是 {@code taskkill /T} 的<b>兜底</b>而非替代:按 PID 精确查询子进程逐个清理,零误杀,无新依赖。
     * 查询+解析形态复刻 {@link #cleanupOrphanedConhosts(long)},仅去掉 {@code name='conhost.exe'} 过滤。
     *
     * @param parentPid 父进程 PID(死活均可,死进程的孤儿子进程也能被查到)
     */
    public static void cleanupChildProcesses(long parentPid) {
        cleanupChildProcesses(parentPid, 0);
    }

    private static void cleanupChildProcesses(long parentPid, int depth) {
        if (!isWindows() || depth > 5) {
            return;
        }
        for (long childPid : queryChildPids(parentPid)) {
            // 先递归清孙进程(bottom-up),再 taskkill /T 杀该子进程及其剩余子树。
            cleanupChildProcesses(childPid, depth + 1);
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "taskkill", "/F", "/T", "/PID", String.valueOf(childPid)
                );
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process killer = pb.start();
                if (!killer.waitFor(5, TimeUnit.SECONDS)) {
                    killer.destroyForcibly();
                }
            } catch (Exception e) {
                LOG.debug("[ProcessCleanup] Failed to terminate child PID " + childPid + ": " + e.getMessage());
            }
        }
    }

    /**
     * 查询 {@code parentPid} 的直接子进程 PID 列表。父进程已死时仍可查到孤儿子进程。
     */
    private static List<Long> queryChildPids(long parentPid) {
        return queryProcessPids("ParentProcessId=" + parentPid);
    }

    /**
     * 按 WQL 条件查询进程 PID 列表(Windows)。
     * <p>
     * 优先 wmic;Win11 24H2+ 已从系统移除 wmic.exe,{@code start()} 会抛 IOException
     * (CreateProcess error=2)——此时回退 PowerShell {@code Get-CimInstance}(同一 WQL
     * 过滤语法,Win10 1607+ 恒可用)。老系统(wmic 存在)行为零变化。
     * <p>
     * 任一路径的输出都解析为「每行一个 PID」(wmic 输出带表头 "ProcessId",跳过;PowerShell
     * Select-Object -ExpandProperty 无表头)。查询失败返回空列表,由调用方各自的兜底语义消化。
     */
    private static List<Long> queryProcessPids(String wqlWhere) {
        try {
            return queryPidsViaWmic(wqlWhere);
        } catch (IOException e) {
            LOG.debug("[ProcessCleanup] wmic unavailable (removed on Win11 24H2+?), falling back to PowerShell: "
                    + e.getMessage());
            return queryPidsViaPowerShell(wqlWhere);
        } catch (Exception e) {
            LOG.debug("[ProcessCleanup] Failed to query processes (" + wqlWhere + "): " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<Long> queryPidsViaWmic(String wqlWhere) throws Exception {
        ProcessBuilder wmicBuilder = new ProcessBuilder(
                "wmic", "process", "where", wqlWhere, "get", "ProcessId"
        );
        wmicBuilder.redirectErrorStream(true);

        Process wmicProcess = wmicBuilder.start();
        String output = readProcessOutput(wmicProcess, 10, TimeUnit.SECONDS);
        return parsePidLines(output, true);
    }

    private static List<Long> queryPidsViaPowerShell(String wqlWhere) {
        List<Long> pids = new ArrayList<>();
        try {
            ProcessBuilder psBuilder = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"" + wqlWhere + "\""
                            + " | Select-Object -ExpandProperty ProcessId"
            );
            psBuilder.redirectErrorStream(true);

            Process psProcess = psBuilder.start();
            // PowerShell 冷启动比 wmic 慢(1-2s),超时相应放宽
            String output = readProcessOutput(psProcess, 20, TimeUnit.SECONDS);
            pids = parsePidLines(output, false);
        } catch (Exception e) {
            LOG.debug("[ProcessCleanup] PowerShell process query failed (" + wqlWhere + "): " + e.getMessage());
        }
        return pids;
    }

    /** 读完查询进程的全部输出并等待其退出(超时强杀),防输出管道堵塞。 */
    private static String readProcessOutput(Process process, long timeout, TimeUnit unit) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        } finally {
            if (!process.waitFor(timeout, unit)) {
                process.destroyForcibly();
            }
        }
        return output.toString();
    }

    /** 解析「每行一个 PID」输出;{@code skipHeader}=true 时跳过 wmic 表头 "ProcessId"。 */
    private static List<Long> parsePidLines(String output, boolean skipHeader) {
        List<Long> pids = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || (skipHeader && trimmed.equals("ProcessId"))) {
                continue;
            }
            try {
                pids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                LOG.debug("[ProcessCleanup] Skipping non-numeric PID line: " + trimmed);
            }
        }
        return pids;
    }

    // ==================== Helper Methods ====================

    /**
     * Get the operating system name.
     *
     * @return the OS name
     */
    public static String getOsName() {
        return System.getProperty("os.name", "Unknown");
    }

    /**
     * Get the user's home directory, bypassing any JVM-level user.home overrides.
     * IDEA may override user.home to a custom directory (e.g. E:/Untitled/IDEA-Jconfig),
     * so this method resolves the real OS home via USERPROFILE (Windows) / HOME (Unix).
     * Falls back to System.getProperty("user.home") if env var is unavailable.
     * Result is cached after first invocation.
     *
     * @return the home directory path
     */
    public static String getHomeDirectory() {
        if (cachedRealHomeDir == null) {
            synchronized (PlatformUtils.class) {
                if (cachedRealHomeDir == null) {
                    String home = null;
                    if (isWindows()) {
                        home = System.getenv("USERPROFILE");
                    } else {
                        home = System.getenv("HOME");
                    }
                    if (home == null || home.isEmpty()) {
                        home = System.getProperty("user.home", "");
                    }
                    cachedRealHomeDir = home;
                }
            }
        }
        return cachedRealHomeDir;
    }

    /**
     * Get the system temporary directory.
     * On Windows, checks TEMP → TMP → java.io.tmpdir in order.
     * On Unix, checks TMPDIR → java.io.tmpdir.
     * Result is cached after first invocation.
     *
     * @return the temporary directory path, or empty string if unavailable
     */
    public static String getTempDirectory() {
        if (cachedTempDir == null) {
            synchronized (PlatformUtils.class) {
                if (cachedTempDir == null) {
                    String tempDir = null;
                    if (isWindows()) {
                        tempDir = getEnvIgnoreCase("TEMP");
                        if (tempDir == null || tempDir.isEmpty()) {
                            tempDir = getEnvIgnoreCase("TMP");
                        }
                    } else {
                        tempDir = System.getenv("TMPDIR");
                    }
                    if (tempDir == null || tempDir.isEmpty()) {
                        tempDir = System.getProperty("java.io.tmpdir", "");
                    }
                    cachedTempDir = tempDir;
                }
            }
        }
        return cachedTempDir;
    }

}
