package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** History archive result wire-field SSOT. */
public enum HistoryArchiveResultPayloadField {
    SUCCESS("success", "boolean", false),
    REQUESTED_SESSION_IDS("requestedSessionIds", "readonly string[]", false),
    ARCHIVED_SESSION_IDS("archivedSessionIds", "readonly string[]", false),
    FAILED_SESSION_IDS("failedSessionIds", "readonly string[]", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    HistoryArchiveResultPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (HistoryArchiveResultPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
