package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Editable SKILL.md field wire-field SSOT. */
public enum SkillDocumentFieldPayloadField {
    KEY("key", "string", false),
    LABEL_KEY("labelKey", "string", false),
    CONTROL("control", "SkillFieldControl", false),
    REQUIRED("required", "boolean", false),
    MAX_LENGTH("maxLength", "number", true),
    PRESENT("present", "boolean", false),
    VALUE("value", "string | boolean | string[] | null", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SkillDocumentFieldPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SkillDocumentFieldPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
