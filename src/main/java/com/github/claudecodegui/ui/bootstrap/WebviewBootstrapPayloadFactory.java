package com.github.claudecodegui.ui.bootstrap;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.avatar.AvatarConfigService;
import com.github.claudecodegui.util.FontConfigService;
import com.github.claudecodegui.util.LanguageConfigService;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

/**
 * Builds the single authoritative bootstrap payload sent from backend to webview.
 */
public final class WebviewBootstrapPayloadFactory {

    private WebviewBootstrapPayloadFactory() {
    }

    public static WebviewBootstrapPayload create(CodemossSettingsService settingsService) {
        return new WebviewBootstrapPayload(
                parseObject(FontConfigService.getEditorFontConfigJson()),
                parseObject(FontConfigService.getResolvedUiFontConfigJson(settingsService)),
                parseObject(FontConfigService.getResolvedCodeFontConfigJson(settingsService)),
                parseObject(LanguageConfigService.getLanguageConfigJson(settingsService)),
                parseObject(CodemossSettingsService.getAppearanceConfigJson(settingsService)),
                parseObject(new AvatarConfigService().serializeAuthoritativeConfig())
        );
    }

    static JsonElement parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return JsonNull.INSTANCE;
        }
        return JsonParser.parseString(json);
    }
}
