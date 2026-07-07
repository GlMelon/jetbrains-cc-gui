package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.CodexSettingsManager;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CLI-only settings facade.
 * Keeps CLI runtime/config lookups isolated from SDK bridge environment wiring.
 */
public final class CliSettings {

    private static final Set<String> PROTECTED_CLI_ENV_KEYS = Set.of(
            "PATH",
            "Path",
            "HOME",
            "USERPROFILE",
            CommonConstants.ENV_HOMEDRIVE,
            CommonConstants.ENV_HOMEPATH,
            CliConstants.ENV_CODEX_HOME,
            CliConstants.ENV_CODEX_SANDBOX,
            CliConstants.ENV_CODEX_SANDBOX_MODE,
            CliConstants.ENV_CODEX_SANDBOX_NETWORK_DISABLED,
            CliConstants.ENV_CLAUDE_SESSION_ID,
            CliConstants.ENV_CLAUDE_PERMISSION_DIR,
            CliConstants.ENV_CLAUDE_PERMISSION_SAFETY_NET_MS,
            CliConstants.ENV_IDEA_PROJECT_PATH,
            CliConstants.ENV_PROJECT_PATH
    );

    // ─── mtime 失效缓存 ───
    // 一轮 Claude send 调 readClaudeCliEnvironment() 3 次(ClaudeCliSession:141/369/370)、Codex 1 次,
    // 每次重读 cli-settings.json + ~/.claude/settings.json(或 config.toml + auth.json) + JSON parse
    // = 一轮 6 次文件 IO + 6 次 parse。缓存以"文件 mtime 未变 + path 未变"为命中条件,用户改 config
    // 下一轮 send 立即生效(mtime 变 → 失效);key 含 path,跨 tempHome 自动失效(对单测透明)。
    // 范式:FileSystemCollector 的 gitignore mtime 缓存。
    private static volatile Path cachedCliSettingsPath;
    private static volatile long cachedCliSettingsMtime;
    private static volatile JsonObject cachedCliSettingsJson;

    private static volatile String cachedClaudeEnvKey;
    private static volatile Map<String, String> cachedClaudeCliEnv;

    private static volatile String cachedCodexEnvKey;
    private static volatile Map<String, String> cachedCodexCliEnv;

    private CliSettings() {
    }

    public static long getClaudePermissionSafetyNetMs() {
        JsonObject cliSettings = readCliSettings();
        if (cliSettings.has(CommonConstants.SETTING_PERMISSION_TIMEOUT)) {
            try {
                int timeoutSeconds = cliSettings.get(CommonConstants.SETTING_PERMISSION_TIMEOUT).getAsInt();
                return (CodemossSettingsService.clampPermissionDialogTimeoutSeconds(timeoutSeconds)
                        + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS) * 1000L;
            } catch (Exception ignored) {
            }
        }
        try {
            long timeoutSeconds = CodemossSettingsService.getInstance().getPermissionDialogTimeoutSeconds();
            return (timeoutSeconds + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS) * 1000L;
        } catch (Exception ignored) {
            return (CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS) * 1000L;
        }
    }

    public static String getCodexSandboxMode(String cwd) {
        JsonObject cliSettings = readCliSettings();
        if (cliSettings.has(CommonConstants.SETTING_CODEX_SANDBOX_MODE)) {
            String configured = safeString(cliSettings, CommonConstants.SETTING_CODEX_SANDBOX_MODE);
            if (CliConstants.VALID_SANDBOX_MODES.contains(configured)) {
                return configured;
            }
        }
        try {
            String configured = CodemossSettingsService.getInstance().getCodexSandboxMode(cwd);
            if (CliConstants.VALID_SANDBOX_MODES.contains(configured)) {
                return configured;
            }
        } catch (Exception ignored) {
        }
        return PlatformUtils.isWindows() ? CliConstants.SANDBOX_DANGER_FULL_ACCESS : CliConstants.SANDBOX_WORKSPACE_WRITE;
    }

    public static JsonObject readClaudeGlobalMcpServers() {
        JsonObject cliSettings = readCliSettings();
        if (cliSettings.has(CliConstants.MCP_SERVERS_KEY) && cliSettings.get(CliConstants.MCP_SERVERS_KEY).isJsonObject()) {
            return cliSettings.getAsJsonObject(CliConstants.MCP_SERVERS_KEY).deepCopy();
        }
        return new JsonObject();
    }

    public static JsonObject readClaudeEnv() {
        JsonObject cliSettings = readCliSettings();
        if (cliSettings.has(CommonConstants.SETTING_CLAUDE_ENV) && cliSettings.get(CommonConstants.SETTING_CLAUDE_ENV).isJsonObject()) {
            return cliSettings.getAsJsonObject(CommonConstants.SETTING_CLAUDE_ENV).deepCopy();
        }
        return new JsonObject();
    }

    public static Map<String, String> readClaudeCliEnvironment() {
        String key = claudeEnvCacheKey();
        if (key != null && key.equals(cachedClaudeEnvKey) && cachedClaudeCliEnv != null) {
            return new LinkedHashMap<>(cachedClaudeCliEnv);
        }
        Map<String, String> env = doReadClaudeCliEnvironment();
        if (key != null) {
            cachedClaudeEnvKey = key;
            cachedClaudeCliEnv = new LinkedHashMap<>(env);
        }
        return env;
    }

    private static String claudeEnvCacheKey() {
        try {
            Path cliSettingsPath = new ConfigPathManager().getCliSettingsFilePath();
            Path homeSettingsPath = Paths.get(PlatformUtils.getHomeDirectory(), CommonConstants.DIR_CLAUDE, CommonConstants.FILE_SETTINGS_JSON);
            long cliMtime = Files.exists(cliSettingsPath) ? Files.getLastModifiedTime(cliSettingsPath).toMillis() : 0L;
            long homeMtime = Files.exists(homeSettingsPath) ? Files.getLastModifiedTime(homeSettingsPath).toMillis() : 0L;
            return cliSettingsPath + "|" + cliMtime + "|" + homeSettingsPath + "|" + homeMtime;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> doReadClaudeCliEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        JsonObject cliOnlyEnv = readClaudeEnv();
        mergeJsonEnvObject(env, cliOnlyEnv);

        try {
            Path settingsPath = Paths.get(PlatformUtils.getHomeDirectory(), CommonConstants.DIR_CLAUDE, CommonConstants.FILE_SETTINGS_JSON);
            if (!Files.exists(settingsPath)) {
                return env;
            }
            JsonObject settings = JsonParser.parseString(Files.readString(settingsPath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (settings != null && settings.has(CommonConstants.TOML_KEY_ENV) && settings.get(CommonConstants.TOML_KEY_ENV).isJsonObject()) {
                mergeJsonEnvObject(env, settings.getAsJsonObject(CommonConstants.TOML_KEY_ENV));
            }
            String apiKeyHelper = safeString(settings, "apiKeyHelper");
            if (apiKeyHelper != null) {
                putIfAllowed(env, CommonConstants.ENV_ANTHROPIC_API_KEY_HELPER, apiKeyHelper);
            }
        } catch (Exception ignored) {
        }
        return env;
    }

    public static Map<String, String> readCodexCliEnvironment() {
        String key = codexEnvCacheKey();
        if (key != null && key.equals(cachedCodexEnvKey) && cachedCodexCliEnv != null) {
            return new LinkedHashMap<>(cachedCodexCliEnv);
        }
        Map<String, String> env = doReadCodexCliEnvironment();
        if (key != null) {
            cachedCodexEnvKey = key;
            cachedCodexCliEnv = new LinkedHashMap<>(env);
        }
        return env;
    }

    private static String codexEnvCacheKey() {
        try {
            Path tomlPath = Paths.get(PlatformUtils.getHomeDirectory(), CommonConstants.DIR_CODEX, "config.toml");
            Path authPath = Paths.get(PlatformUtils.getHomeDirectory(), CommonConstants.DIR_CODEX, CommonConstants.FILE_AUTH_JSON);
            long tomlMtime = Files.exists(tomlPath) ? Files.getLastModifiedTime(tomlPath).toMillis() : 0L;
            long authMtime = Files.exists(authPath) ? Files.getLastModifiedTime(authPath).toMillis() : 0L;
            return tomlPath + "|" + tomlMtime + "|" + authPath + "|" + authMtime;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> doReadCodexCliEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        try {
            CodexSettingsManager manager = new CodexSettingsManager(GsonHolder.GSON);
            Map<String, Object> config = manager.readConfigToml();
            if (config != null) {
                String model = stringValue(config.get(CommonConstants.TOML_KEY_MODEL));
                if (model != null) {
                    putIfAllowed(env, CliConstants.ENV_CODEX_MODEL, model);
                }

                Object envSection = config.get(CommonConstants.TOML_KEY_ENV);
                if (envSection instanceof Map<?, ?> envMap) {
                    mergeObjectEnvMap(env, envMap);
                }

                String providerId = stringValue(config.get(CommonConstants.TOML_KEY_MODEL_PROVIDER));
                Object providers = config.get(CommonConstants.TOML_KEY_MODEL_PROVIDERS);
                if (providerId != null && providers instanceof Map<?, ?> providerMap) {
                    Object providerConfig = providerMap.get(providerId);
                    if (providerConfig instanceof Map<?, ?> providerConfigMap) {
                        String baseUrl = stringValue(providerConfigMap.get(CommonConstants.TOML_KEY_BASE_URL));
                        if (baseUrl != null) {
                            putIfAllowed(env, CliConstants.ENV_OPENAI_BASE_URL, baseUrl);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Path authPath = Paths.get(PlatformUtils.getHomeDirectory(), CommonConstants.DIR_CODEX, CommonConstants.FILE_AUTH_JSON);
            if (Files.exists(authPath)) {
                JsonObject auth = JsonParser.parseString(Files.readString(authPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (auth != null) {
                    mergeKnownCodexAuthEnv(env, auth);
                }
            }
        } catch (Exception ignored) {
        }
        return env;
    }

    /**
     * 读取 ~/.codex/config.toml 中配置的 model 字段。
     * <p>治本:codex 默认 model 应来自用户配置而非硬编码,避免自定义 provider 不支持硬编码 model
     * 而触发上游 502 重连死循环,导致子进程永不退出、前端无限停留在加载状态。
     *
     * @return 配置的 model;无 config 或无 model 字段时返回 null(由调用方回退)。
     */
    public static String readCodexConfigModel() {
        try {
            CodexSettingsManager manager = new CodexSettingsManager(GsonHolder.GSON);
            Map<String, Object> config = manager.readConfigToml();
            if (config != null) {
                return stringValue(config.get(CommonConstants.TOML_KEY_MODEL));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 测试钩子:清空所有 CliSettings 缓存,强制下次重读。 */
    static void __clearCacheForTests() {
        cachedCliSettingsPath = null;
        cachedCliSettingsMtime = 0L;
        cachedCliSettingsJson = null;
        cachedClaudeEnvKey = null;
        cachedClaudeCliEnv = null;
        cachedCodexEnvKey = null;
        cachedCodexCliEnv = null;
    }

    private static JsonObject readCliSettings() {
        Path path;
        try {
            path = new ConfigPathManager().getCliSettingsFilePath();
        } catch (Exception ignored) {
            return new JsonObject();
        }
        long mtime;
        try {
            mtime = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception ignored) {
            mtime = 0L;
        }
        if (path.equals(cachedCliSettingsPath) && mtime == cachedCliSettingsMtime && cachedCliSettingsJson != null) {
            return cachedCliSettingsJson;
        }
        try {
            JsonObject json;
            if (!Files.exists(path)) {
                json = new JsonObject();
            } else {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                json = parsed != null ? parsed : new JsonObject();
            }
            cachedCliSettingsPath = path;
            cachedCliSettingsMtime = mtime;
            cachedCliSettingsJson = json;
            return json;
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String safeString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = obj.get(key).getAsString();
            return value != null ? value.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void mergeJsonEnvObject(Map<String, String> target, JsonObject env) {
        if (target == null || env == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : env.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            try {
                putIfAllowed(target, entry.getKey(), entry.getValue().getAsString());
            } catch (Exception ignored) {
            }
        }
    }

    private static void mergeObjectEnvMap(Map<String, String> target, Map<?, ?> env) {
        if (target == null || env == null) {
            return;
        }
        for (Map.Entry<?, ?> entry : env.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            putIfAllowed(target, String.valueOf(entry.getKey()), stringValue(entry.getValue()));
        }
    }

    private static void mergeKnownCodexAuthEnv(Map<String, String> target, JsonObject auth) {
        for (String key : CliConstants.CODEX_AUTH_ENV_KEYS) {
            String value = safeString(auth, key);
            if (value != null) {
                putIfAllowed(target, key, value);
            }
        }
    }

    private static void putIfAllowed(Map<String, String> target, String key, String value) {
        if (target == null || !isAllowedCliEnvKey(key) || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value.trim());
    }

    private static boolean isAllowedCliEnvKey(String key) {
        if (key == null || key.isBlank() || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return false;
        }
        if (PROTECTED_CLI_ENV_KEYS.contains(key)) {
            return false;
        }
        String upper = key.toUpperCase(Locale.ROOT);
        return !PROTECTED_CLI_ENV_KEYS.contains(upper);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
