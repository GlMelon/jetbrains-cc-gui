package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Webview bootstrap top-level wire-field SSOT. */
public enum WebviewBootstrapPayloadField {
    EDITOR_FONT_CONFIG("editorFontConfig", "unknown", false),
    UI_FONT_CONFIG("uiFontConfig", "unknown", false),
    CODE_FONT_CONFIG("codeFontConfig", "unknown", false),
    LANGUAGE_CONFIG("languageConfig", "unknown", false),
    APPEARANCE_CONFIG("appearanceConfig", "unknown", false),
    AVATAR_CONFIG("avatarConfig", "unknown", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    WebviewBootstrapPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() {
        return wireKey;
    }

    public String tsType() {
        return tsType;
    }

    public boolean optional() {
        return optional;
    }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (WebviewBootstrapPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
