package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Active child-process counts wire-field SSOT. */
public enum NodeProcessActiveProcessesPayloadField {
    NODE("node", "number", false),
    CLI("cli", "number", false),
    MCP("mcp", "number", false),
    ALL("all", "number", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessActiveProcessesPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessActiveProcessesPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
