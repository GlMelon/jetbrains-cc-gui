package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.PlatformUtils;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds an isolated environment for CLI runtimes so they do not inherit
 * SDK-only or host sandbox variables from the parent IDE process.
 */
public final class CliEnvironmentBuilder {

    private CliEnvironmentBuilder() {
    }

    public static Map<String, String> buildBaseEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : CliConstants.WINDOWS_SYSTEM_ENV_KEYS) {
            copyIfPresent(env, key);
        }
        copyPath(env);
        copyHome(env);
        copyProxy(env);
        copyTerminalHints(env);
        ensureCodexHome(env);
        return env;
    }

    public static void configureProjectPath(Map<String, String> env, String cwd) {
        if (env == null || cwd == null || cwd.isBlank() || CommonConstants.UNDEFINED.equals(cwd) || CommonConstants.NULL_SENTINEL.equals(cwd)) {
            return;
        }
        env.put(CliConstants.ENV_IDEA_PROJECT_PATH, cwd);
        env.put(CliConstants.ENV_PROJECT_PATH, cwd);
    }

    public static void configureClaudePermissionEnv(
            Map<String, String> env,
            String permissionDir,
            String sessionId,
            long safetyNetMs
    ) {
        if (env == null) {
            return;
        }
        if (permissionDir != null && !permissionDir.isBlank()) {
            env.put(CliConstants.ENV_CLAUDE_PERMISSION_DIR, permissionDir);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            env.put(CliConstants.ENV_CLAUDE_SESSION_ID, sessionId);
        }
        env.put(CliConstants.ENV_CLAUDE_PERMISSION_SAFETY_NET_MS, String.valueOf(safetyNetMs));
    }

    /**
     * 合并请求级 extraEnv 到 CLI 进程环境。
     * <p>
     * 三 provider(Claude/Codex/OpenCode)对称调用:CLI 模式下把 {@code request.extraEnv()}
     * (如临时 token / base_url 覆盖 / proxy)注入子进程环境,请求级变量覆盖已有值。
     * Codex 在调用前额外 {@code sanitizeEnv} 过滤受保护 key(A5/C5 安全层),其余 provider 直接合并。
     * <p>
     * 抽取为纯函数是因为注入点位于平台耦合的 runOnce(ProcessBuilder.environment()),
     * 无法直接单测;此函数让三 provider 注入逻辑对称且可验证。
     */
    public static void applyExtraEnv(Map<String, String> env, Map<String, String> extraEnv) {
        if (env == null || extraEnv == null || extraEnv.isEmpty()) {
            return;
        }
        env.putAll(extraEnv);
    }

    private static void copyPath(Map<String, String> env) {
        // 用 UserPathResolver 解析用户真实 PATH(IDE PATH + npm/scoop/volta/nodejs/bun 等 shim 目录),
        // 修复 Windows 下 CLI 模式找不到经 npm 全局 / scoop / volta 装的二进制(codex/opencode 等)。
        // (IDE 进程 PATH ≠ 登录 shell PATH;登录 shell 才含这些 shim 目录)
        String path = UserPathResolver.resolveUserPath();
        if (path == null || path.isBlank()) {
            return;
        }
        env.put("PATH", path);
        if (PlatformUtils.isWindows()) {
            env.put("Path", path);
        }
    }

    private static void copyHome(Map<String, String> env) {
        String home = PlatformUtils.getHomeDirectory();
        if (home != null && !home.isBlank()) {
            env.put("HOME", home);
            if (PlatformUtils.isWindows()) {
                env.put("USERPROFILE", home);
            }
        }
        copyIfPresent(env, CommonConstants.ENV_HOMEDRIVE);
        copyIfPresent(env, CommonConstants.ENV_HOMEPATH);
    }

    private static void copyProxy(Map<String, String> env) {
        for (String key : CliConstants.PROXY_ENV_KEYS) {
            copyIfPresent(env, key);
            copyIfPresent(env, key.toLowerCase(Locale.ROOT));
        }
    }

    private static void copyTerminalHints(Map<String, String> env) {
        for (String key : CliConstants.TERMINAL_HINT_ENV_KEYS) {
            copyIfPresent(env, key);
        }
    }

    private static void ensureCodexHome(Map<String, String> env) {
        if (env.containsKey(CliConstants.ENV_CODEX_HOME) && !env.get(CliConstants.ENV_CODEX_HOME).isBlank()) {
            return;
        }
        String home = env.get("HOME");
        if (home == null || home.isBlank()) {
            home = PlatformUtils.getHomeDirectory();
        }
        if (home != null && !home.isBlank()) {
            env.put(CliConstants.ENV_CODEX_HOME, Paths.get(home, CommonConstants.DIR_CODEX).toString());
        }
    }

    private static void copyIfPresent(Map<String, String> env, String key) {
        String value = PlatformUtils.isWindows() ? PlatformUtils.getEnvIgnoreCase(key) : System.getenv(key);
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }
}
