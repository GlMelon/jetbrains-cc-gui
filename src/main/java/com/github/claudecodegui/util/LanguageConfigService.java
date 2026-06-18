package com.github.claudecodegui.util;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.DynamicBundle;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Language configuration service.
 * Retrieves the current language setting from IDEA and provides it to the Webview.
 * Also supports saving user's manual language preference, which takes priority over IDEA's language.
 */
public class LanguageConfigService {

    private static final Logger LOG = Logger.getInstance(LanguageConfigService.class);
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "zh", "en", "zh-TW", "hi", "es", "fr", "ja", "ru", "ko", "pt-BR"
    );

    // 语言/国家码常量：用于逻辑判断与 fallback 默认值。
    // 注意：SUPPORTED_LANGUAGES 内的字面量是校验数据集（数据声明，非逻辑分支），保持原样。
    private static final String LANG_EN = "en";
    private static final String LANG_ZH = "zh";
    private static final String LANG_ZH_TW = "zh-TW";
    private static final String LANG_PT_BR = "pt-BR";
    private static final String COUNTRY_TW = "TW";
    private static final String COUNTRY_HK = "HK";

    /** IDEA 语言码 → i18n 语言码直接映射（"zh" 简繁特殊处理在 mapIdeaLocaleToI18n 单独保留）。 */
    private static final Map<String, String> IDEA_LANG_TO_I18N = Map.of(
            "en", LANG_EN,
            "hi", "hi",
            "es", "es",
            "fr", "fr",
            "ja", "ja",
            "ru", "ru",
            "ko", "ko",
            "pt", LANG_PT_BR
    );

    /**
     * Map IDEA locale codes to i18n-supported language codes.
     * IDEA locale format: zh_CN, en, ja, ko, etc.
     * Supported i18n languages: zh, en, zh-TW, hi, es, fr, ja, ru, ko, pt-BR
     *
     * @param ideaLocale the IDEA Locale
     * @return the i18n language code
     */
    private static String mapIdeaLocaleToI18n(Locale ideaLocale) {
        if (ideaLocale == null) {
            return LANG_EN;  // default to English
        }

        String language = ideaLocale.getLanguage();
        String country = ideaLocale.getCountry();

        // 中文特殊处理：区分简体/繁体
        if (LANG_ZH.equals(language)) {
            if (COUNTRY_TW.equals(country) || COUNTRY_HK.equals(country)) {
                return LANG_ZH_TW;  // 繁体中文
            }
            return LANG_ZH;  // 简体中文
        }

        // 其他语言通过映射表直接查找
        String mapped = IDEA_LANG_TO_I18N.get(language);
        if (mapped != null) {
            return mapped;
        }

        // 不支持的语言，回退到英文
        LOG.info("[LanguageConfig] Unsupported language '" + language + "', falling back to English");
        return LANG_EN;
    }

    /**
     * Get user's manually set language preference.
     *
     * @return the user's language preference, or null if not manually set
     */
    public static String getUserLanguage(CodemossSettingsService settingsService) {
        if (settingsService == null) {
            return null;
        }
        try {
            String userLanguage = settingsService.getUserLanguage();
            if (userLanguage == null || userLanguage.isEmpty()) {
                return null;
            }
            if (!SUPPORTED_LANGUAGES.contains(userLanguage)) {
                LOG.warn("[LanguageConfig] Ignoring unsupported user language in ~/.codemoss/config.json: " + userLanguage);
                return null;
            }
            LOG.info("[LanguageConfig] User manually set language: " + userLanguage);
            return userLanguage;
        } catch (Exception e) {
            LOG.warn("[LanguageConfig] Failed to read user language from ~/.codemoss/config.json: " + e.getMessage());
            return null;
        }
    }

    /**
     * Set user's manual language preference.
     *
     * @param language the language code to save
     */
    public static void setUserLanguage(CodemossSettingsService settingsService, String language) throws IOException {
        if (settingsService == null) {
            throw new IllegalArgumentException("settingsService must not be null");
        }
        if (language == null || !SUPPORTED_LANGUAGES.contains(language.trim())) {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
        settingsService.setUserLanguage(language.trim());
        LOG.info("[LanguageConfig] Saved user language preference: " + language);
    }

    /**
     * Clear user's manual language preference (reset to follow IDEA language).
     */
    public static void clearUserLanguage(CodemossSettingsService settingsService) throws IOException {
        if (settingsService == null) {
            throw new IllegalArgumentException("settingsService must not be null");
        }
        settingsService.clearUserLanguage();
        LOG.info("[LanguageConfig] Cleared user language preference, will follow IDEA language");
    }

    /**
     * Get the current language configuration.
     * If user has manually set a language, use that; otherwise use IDEA's language.
     *
     * @return a JsonObject containing the language configuration
     */
    public static JsonObject getLanguageConfig(CodemossSettingsService settingsService) {
        JsonObject config = new JsonObject();

        try {
            // Check if user has manually set a language preference
            String userLanguage = getUserLanguage(settingsService);

            if (userLanguage != null && !userLanguage.isEmpty()) {
                // Use user's manual language preference
                config.addProperty("language", userLanguage);
                config.addProperty("source", "user");
                config.addProperty("ideaLocale", "");

                LOG.info("[LanguageConfig] Using user's manual language: " + userLanguage);
            } else {
                // Use IDEA's language setting
                Locale currentLocale = DynamicBundle.getLocale();
                String i18nLanguage = mapIdeaLocaleToI18n(currentLocale);

                config.addProperty("language", i18nLanguage);
                config.addProperty("source", "idea");
                config.addProperty("ideaLocale", currentLocale.toString());

                LOG.info("[LanguageConfig] Using IDEA language config: ideaLocale=" + currentLocale
                        + ", i18nLanguage=" + i18nLanguage);
            }

        } catch (Exception e) {
            // Fall back to English on exception
            config.addProperty("language", LANG_EN);
            config.addProperty("source", "fallback");
            config.addProperty("ideaLocale", LANG_EN);
            LOG.error("[LanguageConfig] Failed to get language config, using default (en): " + e.getMessage(), e);
        }

        return config;
    }

    /**
     * Get the language configuration as a JSON string.
     *
     * @return the JSON string
     */
    public static String getLanguageConfigJson(CodemossSettingsService settingsService) {
        return getLanguageConfig(settingsService).toString();
    }

    /**
     * Get the current i18n language code.
     *
     * @return the language code (zh, en, zh-TW, hi, es, fr, ja, ru, ko, pt-BR)
     */
    public static String getCurrentLanguage(CodemossSettingsService settingsService) {
        String userLanguage = getUserLanguage(settingsService);
        if (userLanguage != null && !userLanguage.isEmpty()) {
            return userLanguage;
        }
        try {
            Locale currentLocale = DynamicBundle.getLocale();
            return mapIdeaLocaleToI18n(currentLocale);
        } catch (Exception e) {
            LOG.error("[LanguageConfig] Failed to get current language: " + e.getMessage());
            return LANG_EN;
        }
    }
}
