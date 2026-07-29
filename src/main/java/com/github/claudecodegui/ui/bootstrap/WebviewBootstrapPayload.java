package com.github.claudecodegui.ui.bootstrap;

import com.google.gson.JsonElement;

/**
 * Typed backend-owned startup payload for webview business configuration.
 */
public record WebviewBootstrapPayload(
        JsonElement editorFontConfig,
        JsonElement uiFontConfig,
        JsonElement codeFontConfig,
        JsonElement languageConfig,
        JsonElement appearanceConfig,
        JsonElement avatarConfig
) {
}
