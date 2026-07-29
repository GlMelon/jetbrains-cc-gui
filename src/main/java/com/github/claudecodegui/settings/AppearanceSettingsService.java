package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.FontConfigService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Appearance + Font 领域 Service。
 *
 * <p>封装 appearance、uiFont 与 codeFont 三段配置的默认值、归一化和持久化编排。
 * 依赖方向为上层 Facade → 本 Service → {@link ConfigStore}；
 * 写操作统一使用 {@link ConfigStore#update(ConfigStore.ConfigMutation)} 覆盖完整
 * read-modify-write 临界区，Facade 仅保留兼容调用面。
 */
public final class AppearanceSettingsService {
    private static final Logger LOG = Logger.getInstance(AppearanceSettingsService.class);

    private final ConfigStore configStore;

    // ==================== Font segment constants (migrated from CSS) ====================

    private static final String UI_FONT_CONFIG_KEY = "uiFont";
    private static final String CODE_FONT_CONFIG_KEY = "codeFont";
    // Shared by both UI font and code font: the persisted JSON keys ("mode" /
    // "customFontPath") and the set of valid modes are identical for the two font kinds,
    // so they reuse these UI_FONT_*-named constants. They are NOT UI-only despite the name.
    private static final String UI_FONT_MODE_KEY = "mode";
    private static final String UI_FONT_CUSTOM_PATH_KEY = "customFontPath";
    private static final Set<String> VALID_UI_FONT_MODES = Set.of(
            FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR,
            FontConfigService.UI_FONT_MODE_CUSTOM_FILE
    );

    // ==================== Appearance segment constants (migrated from CSS) ====================

    // Appearance config (theme preference / font size / diff theme / per-theme colors).
    // Persisted so the webview can restore appearance after IDE cache invalidation
    // (localStorage otherwise lives inside the wiped JCEF cache directory).
    private static final String APPEARANCE_CONFIG_KEY = "appearance";
    private static final String APPEARANCE_THEME_PREFERENCE_KEY = "themePreference";
    private static final String APPEARANCE_FONT_SIZE_KEY = "fontSizeLevel";
    private static final String APPEARANCE_DIFF_THEME_KEY = "diffTheme";
    private static final String APPEARANCE_CHAT_BG_KEY = "chatBgColor";
    private static final String APPEARANCE_USER_MSG_KEY = "userMsgColor";
    private static final Set<String> VALID_THEME_PREFERENCES = Set.of("system", "light", "dark");
    private static final Set<String> VALID_DIFF_THEMES = Set.of("follow", "editor", "light", "soft-dark");
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public AppearanceSettingsService(ConfigStore configStore) {
        this.configStore = configStore;
    }

    // ==================== Appearance public API (called by CSS delegates) ====================

    /** Read normalized appearance config; missing segment → defaults. */
    public JsonObject getAppearanceConfig() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(APPEARANCE_CONFIG_KEY) || !config.get(APPEARANCE_CONFIG_KEY).isJsonObject()) {
            return createDefaultAppearanceConfig();
        }
        return normalizeAppearanceConfig(config.getAsJsonObject(APPEARANCE_CONFIG_KEY));
    }

    /** Persist appearance config (called from webview via {@code set_appearance_config}). */
    public void setAppearanceConfig(JsonObject rawConfig) throws IOException {
        JsonObject normalized = normalizeAppearanceConfig(rawConfig);
        configStore.update(config -> config.add(APPEARANCE_CONFIG_KEY, normalized));
        LOG.debug("[AppearanceSettings] Updated appearance config");
    }

    // ==================== Font public API (called by CSS delegates) ====================

    /** Read normalized UI font configuration; missing segment → defaults. */
    public JsonObject getUiFontConfig() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(UI_FONT_CONFIG_KEY) || !config.get(UI_FONT_CONFIG_KEY).isJsonObject()) {
            return createDefaultUiFontConfig();
        }
        return normalizeUiFontConfig(config.getAsJsonObject(UI_FONT_CONFIG_KEY));
    }

    /** Persist UI font configuration. */
    public void setUiFontConfig(String mode, String customFontPath) throws IOException {
        JsonObject fontConfig = createUiFontConfig(mode, customFontPath);
        configStore.update(config -> config.add(UI_FONT_CONFIG_KEY, fontConfig));
        LOG.debug("[AppearanceSettings] Set UI font config: mode=" + mode
                + ", customFontPath=" + customFontPath);
    }

    /** Read normalized code font configuration; missing segment → defaults. */
    public JsonObject getCodeFontConfig() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(CODE_FONT_CONFIG_KEY) || !config.get(CODE_FONT_CONFIG_KEY).isJsonObject()) {
            return createDefaultCodeFontConfig();
        }
        return normalizeCodeFontConfig(config.getAsJsonObject(CODE_FONT_CONFIG_KEY));
    }

    /** Persist code font configuration. */
    public void setCodeFontConfig(String mode, String customFontPath) throws IOException {
        JsonObject fontConfig = createCodeFontConfig(mode, customFontPath);
        configStore.update(config -> config.add(CODE_FONT_CONFIG_KEY, fontConfig));
        LOG.debug("[AppearanceSettings] Set code font config: mode=" + mode
                + ", customFontPath=" + customFontPath);
    }

    // ==================== Appearance private helpers (migrated verbatim from CSS) ====================

    private JsonObject createDefaultAppearanceConfig() {
        JsonObject appearance = new JsonObject();
        appearance.addProperty(APPEARANCE_THEME_PREFERENCE_KEY, "system");
        appearance.addProperty(APPEARANCE_FONT_SIZE_KEY, 2);
        appearance.addProperty(APPEARANCE_DIFF_THEME_KEY, "follow");
        // Colors are omitted by default (unset → webview falls back to theme defaults).
        return appearance;
    }

    /**
     * Normalize and validate an appearance config payload. Unknown/invalid fields
     * fall back to defaults; per-theme colors only persist valid hex values.
     */
    private JsonObject normalizeAppearanceConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultAppearanceConfig();
        }
        JsonObject appearance = new JsonObject();

        // Theme preference (system | light | dark)
        String themePref = rawConfig.has(APPEARANCE_THEME_PREFERENCE_KEY)
                && !rawConfig.get(APPEARANCE_THEME_PREFERENCE_KEY).isJsonNull()
                ? rawConfig.get(APPEARANCE_THEME_PREFERENCE_KEY).getAsString()
                : "system";
        appearance.addProperty(APPEARANCE_THEME_PREFERENCE_KEY,
                VALID_THEME_PREFERENCES.contains(themePref) ? themePref : "system");

        // Font size level (1-6, default 2)
        int fontSizeLevel = 2;
        if (rawConfig.has(APPEARANCE_FONT_SIZE_KEY) && rawConfig.get(APPEARANCE_FONT_SIZE_KEY).isJsonPrimitive()) {
            try {
                int v = rawConfig.get(APPEARANCE_FONT_SIZE_KEY).getAsInt();
                if (v >= 1 && v <= 6) {
                    fontSizeLevel = v;
                }
            } catch (Exception ignored) {
                // Non-numeric value → fall back to default
            }
        }
        appearance.addProperty(APPEARANCE_FONT_SIZE_KEY, fontSizeLevel);

        // Diff theme
        String diffTheme = rawConfig.has(APPEARANCE_DIFF_THEME_KEY)
                && !rawConfig.get(APPEARANCE_DIFF_THEME_KEY).isJsonNull()
                ? rawConfig.get(APPEARANCE_DIFF_THEME_KEY).getAsString()
                : "follow";
        appearance.addProperty(APPEARANCE_DIFF_THEME_KEY,
                VALID_DIFF_THEMES.contains(diffTheme) ? diffTheme : "follow");

        // Per-theme colors (only valid hex values are persisted)
        JsonObject chatBg = normalizeScopedColors(rawConfig, APPEARANCE_CHAT_BG_KEY);
        if (chatBg != null) {
            appearance.add(APPEARANCE_CHAT_BG_KEY, chatBg);
        }
        JsonObject userMsg = normalizeScopedColors(rawConfig, APPEARANCE_USER_MSG_KEY);
        if (userMsg != null) {
            appearance.add(APPEARANCE_USER_MSG_KEY, userMsg);
        }

        return appearance;
    }

    /** Normalize a per-theme color map ({light, dark}); null if no valid entries. */
    private JsonObject normalizeScopedColors(JsonObject rawConfig, String key) {
        if (!rawConfig.has(key) || !rawConfig.get(key).isJsonObject()) {
            return null;
        }
        JsonObject src = rawConfig.getAsJsonObject(key);
        JsonObject out = new JsonObject();
        addHexIfValid(out, src, "light");
        addHexIfValid(out, src, "dark");
        return out.size() > 0 ? out : null;
    }

    private void addHexIfValid(JsonObject out, JsonObject src, String theme) {
        if (!src.has(theme) || !src.get(theme).isJsonPrimitive()) {
            return;
        }
        String v = src.get(theme).getAsString();
        if (v != null && HEX_COLOR_PATTERN.matcher(v).matches()) {
            out.addProperty(theme, v);
        }
    }

    // ==================== Font private helpers (migrated verbatim from CSS) ====================

    private JsonObject createDefaultUiFontConfig() {
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
        return uiFont;
    }

    private JsonObject createDefaultCodeFontConfig() {
        JsonObject codeFont = new JsonObject();
        codeFont.addProperty(UI_FONT_MODE_KEY, FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
        return codeFont;
    }

    private JsonObject normalizeUiFontConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultUiFontConfig();
        }
        String requestedMode = rawConfig.has(UI_FONT_MODE_KEY) && !rawConfig.get(UI_FONT_MODE_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_MODE_KEY).getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        String customFontPath = rawConfig.has(UI_FONT_CUSTOM_PATH_KEY) && !rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).getAsString()
                : null;
        return createUiFontConfig(requestedMode, customFontPath);
    }

    private JsonObject createUiFontConfig(String mode, String customFontPath) {
        String normalizedMode = VALID_UI_FONT_MODES.contains(mode)
                ? mode
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, normalizedMode);

        if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(normalizedMode)
                && customFontPath != null
                && !customFontPath.trim().isEmpty()) {
            uiFont.addProperty(UI_FONT_CUSTOM_PATH_KEY, customFontPath.trim());
        }

        return uiFont;
    }

    private JsonObject normalizeCodeFontConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultCodeFontConfig();
        }
        String requestedMode = rawConfig.has(UI_FONT_MODE_KEY) && !rawConfig.get(UI_FONT_MODE_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_MODE_KEY).getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        String customFontPath = rawConfig.has(UI_FONT_CUSTOM_PATH_KEY) && !rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).getAsString()
                : null;
        return createCodeFontConfig(requestedMode, customFontPath);
    }

    private JsonObject createCodeFontConfig(String mode, String customFontPath) {
        // UI font and code font share the same valid-mode set (see VALID_UI_FONT_MODES).
        String normalizedMode = VALID_UI_FONT_MODES.contains(mode)
                ? mode
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        JsonObject codeFont = new JsonObject();
        codeFont.addProperty(UI_FONT_MODE_KEY, normalizedMode);

        if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(normalizedMode)
                && customFontPath != null
                && !customFontPath.trim().isEmpty()) {
            codeFont.addProperty(UI_FONT_CUSTOM_PATH_KEY, customFontPath.trim());
        }

        return codeFont;
    }
}
