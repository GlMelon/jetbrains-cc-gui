package com.github.claudecodegui.cli.common.normalizer;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Builds provider_event diagnostic blocks while removing sensitive provider-only fields.
 */
public final class UnknownProviderEventBuilder {
    private final String provider;

    public UnknownProviderEventBuilder(String provider) {
        this.provider = provider;
    }

    public JsonObject build(String eventType, String itemType, JsonObject raw) {
        JsonObject sanitized = sanitize(raw);
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_PROVIDER_EVENT);
        block.addProperty(CommonConstants.JSON_KEY_PROVIDER, provider);
        addIfPresent(block, CommonConstants.JSON_KEY_EVENT_TYPE, eventType);
        addIfPresent(block, CommonConstants.JSON_KEY_ITEM_TYPE, itemType);
        block.addProperty(CommonConstants.JSON_KEY_SUMMARY, summary(eventType, itemType));
        if (sanitized != null && sanitized.size() > 0) {
            block.addProperty(CommonConstants.JSON_KEY_DETAILS, sanitized.toString());
            block.add(CommonConstants.JSON_KEY_RAW, sanitized);
        }
        return block;
    }

    public JsonObject sanitize(JsonObject raw) {
        JsonElement sanitized = sanitizeElement(raw);
        return sanitized != null && sanitized.isJsonObject() ? sanitized.getAsJsonObject() : new JsonObject();
    }

    public boolean hasOnlyEncryptedContent(JsonObject raw) {
        if (raw == null || raw.size() == 0) {
            return false;
        }
        if (!containsEncryptedContent(raw)) {
            return false;
        }
        JsonObject sanitized = sanitize(raw);
        return !hasVisibleContent(sanitized, true);
    }

    private boolean hasVisibleContent(JsonElement element, boolean root) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                return !element.getAsString().isBlank();
            }
            return true;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (hasVisibleContent(child, false)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (root && isMetadataKey(entry.getKey())) {
                continue;
            }
            if (hasVisibleContent(entry.getValue(), false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMetadataKey(String key) {
        return CommonConstants.JSON_KEY_TYPE.equals(key)
                || CommonConstants.JSON_KEY_ID.equals(key)
                || CommonConstants.JSON_KEY_STATUS.equals(key);
    }

    private JsonElement sanitizeElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                JsonElement sanitizedChild = sanitizeElement(child);
                if (sanitizedChild != null) {
                    array.add(sanitizedChild);
                }
            }
            return array;
        }
        if (!element.isJsonObject()) {
            return element.deepCopy();
        }

        JsonObject object = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (CliConstants.CODEX_FIELD_ENCRYPTED_CONTENT.equals(entry.getKey())) {
                continue;
            }
            JsonElement sanitizedChild = sanitizeElement(entry.getValue());
            if (sanitizedChild != null) {
                object.add(entry.getKey(), sanitizedChild);
            }
        }
        return object;
    }

    private boolean containsEncryptedContent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsEncryptedContent(child)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (CliConstants.CODEX_FIELD_ENCRYPTED_CONTENT.equals(entry.getKey())
                    || containsEncryptedContent(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String summary(String eventType, String itemType) {
        String effectiveType = itemType != null && !itemType.isBlank() ? itemType : eventType;
        return effectiveType == null || effectiveType.isBlank()
                ? CommonConstants.BLOCK_SUMMARY_PROVIDER_EVENT
                : CommonConstants.BLOCK_SUMMARY_PROVIDER_EVENT + CommonConstants.BLOCK_SUMMARY_SEPARATOR + effectiveType;
    }

    private static void addIfPresent(JsonObject block, String key, String value) {
        if (value != null && !value.isBlank()) {
            block.addProperty(key, value);
        }
    }
}
