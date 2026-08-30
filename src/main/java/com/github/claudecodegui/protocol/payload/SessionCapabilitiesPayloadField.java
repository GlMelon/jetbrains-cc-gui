package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Current-session capability snapshot wire-field SSOT. */
public enum SessionCapabilitiesPayloadField {
    SESSION_ID("sessionId", "string", false),
    RUNTIME_EPOCH("runtimeEpoch", "string", false),
    PROVIDER("provider", "string", false),
    OBSERVED_AT("observedAt", "number", false),
    STATE("state", "string", false),
    CHANNEL("channel", "string", false),
    THINKING_AVAILABLE("thinkingAvailable", "boolean | null", false),
    TOOLS_AVAILABLE("toolsAvailable", "boolean | null", false),
    SESSION_MCP_AVAILABLE("sessionMcpAvailable", "boolean | null", false),
    DEGRADED("degraded", "boolean", false),
    DEGRADATION_REASON("degradationReason", "string | null", false),
    MCP_AVAILABLE("mcpAvailable", "boolean", false),
    MCP_ERROR("mcpError", "string | null", false),
    MCP("mcp", "SessionMcpCapabilityPayloadWire[]", false),
    SKILLS("skills", "SessionSkillCapabilityPayloadWire[]", false);

    /** Wrapper key used when attaching a snapshot to a historical session entry. */
    public static final String HISTORY_SESSION_WIRE_KEY = "sessionCapabilities";

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SessionCapabilitiesPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SessionCapabilitiesPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
