package com.github.claudecodegui.protocol.payload;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * History export downstream payload wire-field SSOT.
 *
 * <p>The generator reads these declarations to produce
 * {@code HistoryExportPayloadWire} for the Webview. Keep this list aligned with
 * {@code HistoryExportPayloadBuilder} output.</p>
 */
public enum HistoryExportPayloadField {
    SUCCESS("success", "boolean", false),
    SESSION_ID("sessionId", "string", true),
    TITLE("title", "string", true),
    FORMAT("format", "HistoryExportFormat", true),
    FILE_NAME("fileName", "string", true),
    MIME_TYPE("mimeType", "string", true),
    CONTENT("content", "string", true),
    TRUNCATED("truncated", "boolean", true),
    EXPORTED_MESSAGE_COUNT("exportedMessageCount", "number", true),
    OMITTED_MESSAGE_COUNT("omittedMessageCount", "number", true),
    MAX_MESSAGE_COUNT("maxMessageCount", "number", true),
    MAX_UTF8_BYTES("maxUtf8Bytes", "number", true),
    ERROR("error", "string", true);

    private final String wireKey;
    private final String tsType;
    private final boolean optional;

    HistoryExportPayloadField(String wireKey, String tsType, boolean optional) {
        this.wireKey = wireKey;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String wireKey() {
        return wireKey;
    }

    public String tsType() {
        return tsType;
    }

    public boolean optional() {
        return optional;
    }

    public static Set<String> wireKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (HistoryExportPayloadField field : values()) {
            keys.add(field.wireKey);
        }
        return keys;
    }
}
