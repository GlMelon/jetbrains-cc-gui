package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pending permission and tool lifecycle diagnostics wire-field SSOT. */
public enum NodeProcessPendingInteractionsPayloadField {
    PENDING_PERMISSION_REQUESTS("pendingPermissionRequests", "number", false),
    PENDING_TOOL_CALLS("pendingToolCalls", "number", false),
    ORPHAN_TOOL_RESULTS("orphanToolResults", "number", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessPendingInteractionsPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessPendingInteractionsPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
