package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Node process grouping totals wire-field SSOT. */
public enum NodeProcessTotalsPayloadField {
    DAEMON("daemon", "number", false),
    CHANNEL("channel", "number", false),
    ORPHAN("orphan", "number", false),
    CLI_SESSION("cliSession", "number", false),
    ALL("all", "number", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    NodeProcessTotalsPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (NodeProcessTotalsPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
