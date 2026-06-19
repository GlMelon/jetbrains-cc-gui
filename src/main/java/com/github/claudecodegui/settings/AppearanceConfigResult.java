package com.github.claudecodegui.settings;

/**
 * Result of applying an appearance config. Always carries the authoritative
 * config JSON (post-write read-back) so the handler can push it to the webview
 * on both success and failure paths.
 */
public final class AppearanceConfigResult {
    private final String configJson;

    public AppearanceConfigResult(String configJson) {
        this.configJson = configJson;
    }

    public String configJson() { return configJson; }
}
