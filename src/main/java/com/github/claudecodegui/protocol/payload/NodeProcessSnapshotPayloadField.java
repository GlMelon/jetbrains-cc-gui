package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Node process panel snapshot wire-field SSOT. */
public enum NodeProcessSnapshotPayloadField {
    SNAPSHOT_AT("snapshotAt", "number", false),
    TOTALS("totals", "NodeProcessTotalsPayloadWire", false),
    PROCESSES("processes", "readonly NodeProcessInfoPayloadWire[]", false),
    DIAGNOSTICS("diagnostics", "NodeProcessDiagnosticsPayloadWire", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessSnapshotPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessSnapshotPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
