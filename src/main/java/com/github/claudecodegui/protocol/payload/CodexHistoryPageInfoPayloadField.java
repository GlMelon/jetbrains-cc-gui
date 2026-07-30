package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Codex history pagination result metadata wire-field SSOT. */
public enum CodexHistoryPageInfoPayloadField {
    PAGE_ID("pageId", "string", false),
    SESSION_ID("sessionId", "string", false),
    MODE("mode", "CodexHistoryPageMode", false),
    FROM_TURN("fromTurn", "number", false),
    TO_TURN("toTurn", "number", false),
    TOTAL_TURNS("totalTurns", "number", false),
    HAS_MORE("hasMore", "boolean", false),
    LOADED_MESSAGE_COUNT("loadedMessageCount", "number", false),
    CURSOR_RESET("cursorReset", "boolean", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    CodexHistoryPageInfoPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (CodexHistoryPageInfoPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
