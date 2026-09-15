package com.github.claudecodegui.i18n;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * webview 语言包(JSON)读取器 —— 后端侧用户可见文案(如标签状态徽标)与
 * webview 共用同一语言 SSOT:{@code webview/src/i18n/locales/*.json} 经构建期
 * processResources 原样同步进插件资源 {@code i18n/locales/},本类按插件语言码
 * (用户手动语言 &gt; IDEA 语言,见 {@code LanguageConfigService})加载并缓存,
 * 键缺失时回落英文包。
 */
public final class WebviewLocaleBundle {

    private static final Logger LOG = Logger.getInstance(WebviewLocaleBundle.class);

    private static final String RESOURCE_DIR = "i18n/locales/";
    private static final String FALLBACK_LANGUAGE = "en";
    private static final Map<String, JsonObject> CACHE = new ConcurrentHashMap<>();

    private WebviewLocaleBundle() {
    }

    /**
     * 读取语言包中指定路径的文案。
     *
     * @param language 插件语言码(zh / en / zh-TW / ja / ru / fr / es / hi / ko / pt-BR)
     * @param path     JSON 键路径,如 ("tabStatus", "queued")
     * @return 文案;语言包与英文包均缺失该键时返回 null
     */
    public static String text(String language, String... path) {
        String value = textIn(localeRoot(language), path);
        if (value == null) {
            return textIn(localeRoot(FALLBACK_LANGUAGE), path);
        }
        return value;
    }

    private static JsonObject localeRoot(String language) {
        return CACHE.computeIfAbsent(normalizeLanguage(language), WebviewLocaleBundle::loadLocale);
    }

    /** 语言码主体小写、地区码大写(zh-tw → zh-TW),与资源文件名精确对应;空值回落英文。 */
    private static String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return FALLBACK_LANGUAGE;
        }
        String trimmed = language.trim();
        int dash = trimmed.indexOf('-');
        if (dash < 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.substring(0, dash).toLowerCase(Locale.ROOT)
                + '-' + trimmed.substring(dash + 1).toUpperCase(Locale.ROOT);
    }

    private static JsonObject loadLocale(String code) {
        String resource = RESOURCE_DIR + code + ".json";
        try (InputStream in = WebviewLocaleBundle.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("Webview locale resource missing: " + resource);
                return new JsonObject();
            }
            return GsonHolder.GSON.fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to load webview locale resource " + resource + ": " + e.getMessage());
            return new JsonObject();
        }
    }

    private static String textIn(JsonObject root, String... path) {
        if (root == null || path.length == 0) {
            return null;
        }
        JsonObject node = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (node.get(path[i]) == null || !node.get(path[i]).isJsonObject()) {
                return null;
            }
            node = node.getAsJsonObject(path[i]);
        }
        String leaf = path[path.length - 1];
        if (node.get(leaf) == null || !node.get(leaf).isJsonPrimitive()) {
            return null;
        }
        String value = node.get(leaf).getAsString();
        return value == null || value.isBlank() ? null : value;
    }
}
