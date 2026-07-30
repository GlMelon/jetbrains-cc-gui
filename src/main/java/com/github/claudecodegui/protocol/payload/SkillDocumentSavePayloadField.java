package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Editable SKILL.md save request wire-field SSOT. */
public enum SkillDocumentSavePayloadField {
    REQUEST_ID("requestId", "string", true),
    SCOPE("scope", "SkillScope", false),
    NAME("name", "string", false),
    DIRECTORY_PATH("directoryPath", "string", false),
    SKILL_PATH("skillPath", "string", false),
    ENABLED("enabled", "boolean", false),
    REVISION("revision", "string", false),
    CHANGES("changes", "Record<string, string | boolean | string[] | null>", false),
    BODY("body", "string", false);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SkillDocumentSavePayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SkillDocumentSavePayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
