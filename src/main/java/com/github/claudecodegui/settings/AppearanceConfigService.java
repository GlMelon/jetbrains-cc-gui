package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Backend service for appearance config persistence.
 * Writes the webview payload and reads back the authoritative config so the
 * handler can hydrate / roll back optimistic UI updates.
 */
public final class AppearanceConfigService {
    private static final Logger LOG = Logger.getInstance(AppearanceConfigService.class);

    private final CodemossSettingsService settingsService;

    public AppearanceConfigService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public AppearanceConfigResult apply(JsonObject rawConfig) {
        try {
            settingsService.setAppearanceConfig(rawConfig);
            LOG.debug("[AppearanceConfigService] Saved appearance config");
        } catch (Exception e) {
            LOG.error("[AppearanceConfigService] Failed to save appearance config: " + e.getMessage(), e);
        }
        return new AppearanceConfigResult(CodemossSettingsService.getAppearanceConfigJson(settingsService));
    }
}
