package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Node process entry wire-field SSOT. */
public enum NodeProcessInfoPayloadField {
    ID("id", "string", false),
    KIND("kind", "NodeProcessKind", false),
    PROVIDER("provider", "string", true),
    PID("pid", "number", false),
    ALIVE("alive", "boolean", false),
    STARTED_AT("startedAt", "number", false),
    UPTIME_MS("uptimeMs", "number", false),
    COMMAND("command", "string", true),
    HEAP_USED("heapUsed", "number", true),
    ACTIVE_REQUEST_COUNT("activeRequestCount", "number", false),
    CHANNEL_ID("channelId", "string", true),
    SESSION_ID("sessionId", "string", true),
    TAB_NAME("tabName", "string", true),
    ORPHAN("orphan", "boolean", false),
    PROJECT_LIFECYCLE_ID("projectLifecycleId", "string", true),
    RUNTIME_SESSION_EPOCH("runtimeSessionEpoch", "string", true),
    RESPONSE_TURN_EPOCH("responseTurnEpoch", "number", true),
    PROCESS_GENERATION("processGeneration", "number", true);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessInfoPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessInfoPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
