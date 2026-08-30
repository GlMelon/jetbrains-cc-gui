package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** MCP gateway lifecycle diagnostics wire-field SSOT. */
public enum NodeProcessGatewayPayloadField {
    LIFECYCLE_STATE("lifecycleState", "string", false),
    LAST_FAILURE("lastFailure", "string | null", false),
    PROCESS_GENERATION("processGeneration", "number", false),
    ACTIVE_PROCESS_COUNT("activeProcessCount", "number", false),
    REFRESH_IN_FLIGHT("refreshInFlight", "boolean", false),
    RESTART_COUNT("restartCount", "number", false),
    LAST_COLD_START_DURATION_MS("lastColdStartDurationMs", "number", false),
    LAST_CATALOG_READY_DURATION_MS("lastCatalogReadyDurationMs", "number", false),
    DIRECT_DEGRADED_COUNT("directDegradedCount", "number", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessGatewayPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessGatewayPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
