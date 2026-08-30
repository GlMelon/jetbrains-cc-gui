package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Persistent CLI registry diagnostics wire-field SSOT. */
public enum NodeProcessPersistentRegistryPayloadField {
    REGISTRY_SIZE("registrySize", "number", false),
    USABLE_PROCESS_COUNT("usableProcessCount", "number", false),
    PENDING_REBUILD_COUNT("pendingRebuildCount", "number", false),
    EVICTION_COUNT("evictionCount", "number", false),
    REBUILD_COOLDOWN_HIT_COUNT("rebuildCooldownHitCount", "number", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessPersistentRegistryPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessPersistentRegistryPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
