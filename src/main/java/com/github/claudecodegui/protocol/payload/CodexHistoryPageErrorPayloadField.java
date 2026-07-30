package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Codex history pagination error wire-field SSOT. */
public enum CodexHistoryPageErrorPayloadField {
    SESSION_ID("sessionId", "string", true),
    ERROR("error", "string", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    CodexHistoryPageErrorPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (CodexHistoryPageErrorPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
