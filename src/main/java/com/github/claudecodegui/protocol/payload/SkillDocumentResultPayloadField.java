package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/** Editable SKILL.md read/save result wire-field SSOT. */
public enum SkillDocumentResultPayloadField {
    SUCCESS("success", "boolean", false),
    REQUEST_ID("requestId", "string", true),
    PROVIDER("provider", "ProviderType", true),
    REVISION("revision", "string", true),
    FILE_NAME("fileName", "string", true),
    BODY("body", "string", true),
    EDITABLE("editable", "boolean", true),
    FIELDS("fields", "SkillDocumentFieldPayloadWire[]", true),
    PARSE_ERROR("parseError", "boolean", true),
    CONFLICT("conflict", "boolean", true),
    CHANGED("changed", "boolean", true),
    BACKUP_PATH("backupPath", "string", true),
    ROLLED_BACK("rolledBack", "boolean", true),
    ERROR("error", "string", true);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    SkillDocumentResultPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() { return wireKey; }
    public String tsType() { return tsType; }
    public boolean optional() { return optional; }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (SkillDocumentResultPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
