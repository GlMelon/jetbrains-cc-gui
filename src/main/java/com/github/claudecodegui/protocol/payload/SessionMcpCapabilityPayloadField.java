package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Session MCP capability item wire-field SSOT. */
public enum SessionMcpCapabilityPayloadField {
    ID("id", "string", false),
    NAME("name", "string", false),
    PROVIDER("provider", "string", false),
    STATE("state", "string", false),
    LAST_ERROR("lastError", "string | null", false),
    LAST_SUCCESS_AT("lastSuccessAt", "number | null", false),
    FAILURE_COUNT("failureCount", "number", false),
    OBSERVED("observed", "boolean", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SessionMcpCapabilityPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SessionMcpCapabilityPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
