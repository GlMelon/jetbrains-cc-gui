package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Backend service for appearance config persistence.
 * Writes the webview payload and reads back the authoritative config so the
 * handler can hydrate / roll back optimistic UI updates.
 *
 * <p>Payload 解析位于 try 块内:畸形 JSON 触发的异常在此被捕获,随后仍无条件回读权威配置——
 * 与原 SettingsHandler.handleSetAppearanceConfig(其中 gson.fromJson 也置于 try 内)逐字等价。
 */
public final class AppearanceConfigService {
    private static final Logger LOG = Logger.getInstance(AppearanceConfigService.class);

    private final CodemossSettingsService settingsService;

    public AppearanceConfigService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public AppearanceConfigResult apply(String payload) {
        try {
            JsonObject rawConfig = GsonHolder.GSON.fromJson(payload, JsonObject.class);
            settingsService.setAppearanceConfig(rawConfig);
            LOG.debug("[AppearanceConfigService] Saved appearance config");
        } catch (Exception e) {
            LOG.error("[AppearanceConfigService] Failed to save appearance config: " + e.getMessage(), e);
        }
        return new AppearanceConfigResult(CodemossSettingsService.getAppearanceConfigJson(settingsService));
    }
}
