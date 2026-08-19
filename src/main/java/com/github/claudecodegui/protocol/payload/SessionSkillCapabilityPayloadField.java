package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Session skill capability item wire-field SSOT. */
public enum SessionSkillCapabilityPayloadField {
    ID("id", "string", false),
    NAME("name", "string", false),
    SCOPE("scope", "string", false),
    STATE("state", "string", false),
    OBSERVED("observed", "boolean", false),
    SOURCE("source", "string", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SessionSkillCapabilityPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SessionSkillCapabilityPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
