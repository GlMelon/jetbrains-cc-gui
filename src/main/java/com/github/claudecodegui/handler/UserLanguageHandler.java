package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.LanguageConfigService;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles user manual language preference (set/get/clear) operations.
 *
 * <p>B3 slice: user-language. 自 {@code SettingsHandler} 迁出,逻辑逐字等价。
 */
public class UserLanguageHandler {

    private static final Logger LOG = Logger.getInstance(UserLanguageHandler.class);

    private final HandlerContext context;
    private final Gson gson = GsonHolder.GSON;

    public UserLanguageHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * set_user_language: save user's manual language preference.
     * On failure, push the authoritative config back so the webview can roll
     * back its optimistic UI update.
     */
    public void handleSetUserLanguage(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String language = json.has("language") && !json.get("language").isJsonNull()
                    ? json.get("language").getAsString() : null;
            if (language == null || language.isEmpty()) {
                LOG.warn("[UserLanguageHandler] set_user_language rejected: empty language");
                pushLanguageConfig();
                return;
            }
            LanguageConfigService.setUserLanguage(context.getSettingsService(), language);
            LOG.info("[UserLanguageHandler] Saved user language preference: " + language);
            pushLanguageConfig();
        } catch (Exception e) {
            LOG.error("[UserLanguageHandler] Failed to save user language: " + e.getMessage(), e);
            pushLanguageConfig();
        }
    }

    /**
     * get_user_language: return user's saved language preference.
     */
    public void handleGetUserLanguage() {
        String userLanguage = LanguageConfigService.getUserLanguage(context.getSettingsService());
        JsonObject response = new JsonObject();
        response.addProperty("language", userLanguage != null ? userLanguage : "");
        response.addProperty("manuallySet", userLanguage != null);
        context.dispatchEvent(DownstreamEvent.LANGUAGE_USER_LANGUAGE.value(), context.escapeJs(response.toString()));
    }

    /**
     * clear_user_language: clear user's manual language preference.
     * Pushes the authoritative config on both success and failure so the
     * webview always reflects the persisted state.
     */
    public void handleClearUserLanguage() {
        try {
            LanguageConfigService.clearUserLanguage(context.getSettingsService());
            LOG.info("[UserLanguageHandler] Cleared user language preference");
        } catch (Exception e) {
            LOG.error("[UserLanguageHandler] Failed to clear user language: " + e.getMessage(), e);
        } finally {
            pushLanguageConfig();
        }
    }

    private void pushLanguageConfig() {
        JsonObject languageConfig = LanguageConfigService.getLanguageConfig(context.getSettingsService());
        context.dispatchEvent(DownstreamEvent.LANGUAGE_APPLY.value(), context.escapeJs(languageConfig.toString()));
    }
}
