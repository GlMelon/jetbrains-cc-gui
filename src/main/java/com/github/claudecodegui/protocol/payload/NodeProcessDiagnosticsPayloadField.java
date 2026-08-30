package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Project runtime diagnostics wrapper wire-field SSOT. */
public enum NodeProcessDiagnosticsPayloadField {
    ACTIVE_PROCESSES("activeProcesses", "NodeProcessActiveProcessesPayloadWire", false),
    PERSISTENT_REGISTRY("persistentRegistry", "NodeProcessPersistentRegistryPayloadWire", false),
    GATEWAY("gateway", "NodeProcessGatewayPayloadWire", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessDiagnosticsPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessDiagnosticsPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
