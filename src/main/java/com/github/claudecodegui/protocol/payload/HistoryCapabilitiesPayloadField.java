package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** History-list capability wire-field SSOT. */
public enum HistoryCapabilitiesPayloadField {
    CAN_DELETE("canDelete", "boolean", false),
    CAN_ARCHIVE("canArchive", "boolean", false);

    public static final String ROOT_WIRE_KEY = "capabilities";

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    HistoryCapabilitiesPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (HistoryCapabilitiesPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
