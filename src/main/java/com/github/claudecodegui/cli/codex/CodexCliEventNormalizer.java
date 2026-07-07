package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.normalizer.NormalizedMessageBlockBuilder;
import com.github.claudecodegui.cli.common.normalizer.UnknownProviderEventBuilder;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * Normalizes Codex CLI JSON items into provider-agnostic assistant blocks.
 */
final class CodexCliEventNormalizer {
    private final NormalizedMessageBlockBuilder messageBlockBuilder = new NormalizedMessageBlockBuilder();
    private final UnknownProviderEventBuilder unknownProviderEventBuilder =
            new UnknownProviderEventBuilder(ProviderType.CODEX.value());

    Optional<JsonObject> normalizeItem(String eventType, JsonObject item) {
        if (item == null || unknownProviderEventBuilder.hasOnlyEncryptedContent(item)) {
            return Optional.empty();
        }
        String itemType = getString(item, CommonConstants.JSON_KEY_TYPE);
        if (itemType == null || itemType.isBlank()) {
            return unknown(eventType, null, item);
        }

        JsonObject block = switch (itemType) {
            case CliConstants.CODEX_ITEM_FILE_CHANGE -> fileChangeBlock(item);
            case CliConstants.CODEX_ITEM_MCP_TOOL_CALL -> mcpToolCallBlock(item);
            case CliConstants.CODEX_ITEM_WEB_SEARCH -> webSearchBlock(item);
            case CliConstants.CODEX_ITEM_TODO_LIST -> todoListBlock(item);
            case CliConstants.CODEX_ITEM_ERROR -> providerErrorBlock(item);
            default -> null;
        };
        if (block == null) {
            return unknown(eventType, itemType, item);
        }
        return Optional.of(messageBlockBuilder.assistantWithBlock(block));
    }

    Optional<JsonObject> normalizePayload(JsonObject payload) {
        if (payload == null || unknownProviderEventBuilder.hasOnlyEncryptedContent(payload)) {
            return Optional.empty();
        }
        String payloadType = getString(payload, CommonConstants.JSON_KEY_TYPE);
        if (payloadType == null || payloadType.isBlank()) {
            return unknown(CliConstants.CODEX_EVENT_RESPONSE_ITEM, null, payload);
        }

        JsonObject block = switch (payloadType) {
            case CliConstants.CODEX_ITEM_FILE_CHANGE -> fileChangeBlock(payload);
            case CliConstants.CODEX_ITEM_MCP_TOOL_CALL -> mcpToolCallBlock(payload);
            case CliConstants.CODEX_ITEM_WEB_SEARCH -> webSearchBlock(payload);
            case CliConstants.CODEX_ITEM_TODO_LIST -> todoListBlock(payload);
            case CliConstants.CODEX_ITEM_ERROR -> providerErrorBlock(payload);
            default -> null;
        };
        if (block == null) {
            return unknown(CliConstants.CODEX_EVENT_RESPONSE_ITEM, payloadType, payload);
        }
        return Optional.of(messageBlockBuilder.assistantWithBlock(block));
    }

    Optional<JsonObject> unknown(String eventType, String itemType, JsonObject raw) {
        if (raw == null || unknownProviderEventBuilder.hasOnlyEncryptedContent(raw)) {
            return Optional.empty();
        }
        return Optional.of(messageBlockBuilder.assistantWithBlock(
                unknownProviderEventBuilder.build(eventType, itemType, raw)
        ));
    }

    private JsonObject fileChangeBlock(JsonObject item) {
        JsonObject block = baseBlock(CommonConstants.BLOCK_TYPE_FILE_CHANGE, CommonConstants.BLOCK_TITLE_FILE_CHANGE);
        String path = firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_PATH),
                getString(item, CliConstants.CODEX_FIELD_FILE_PATH),
                getString(item, CliConstants.CODEX_FIELD_FILE),
                getString(item, CliConstants.CODEX_FIELD_FILENAME)
        );
        String operation = firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_OPERATION),
                getString(item, CliConstants.CODEX_FIELD_ACTION),
                getString(item, CliConstants.CODEX_FIELD_CHANGE_TYPE)
        );
        addIfPresent(block, CommonConstants.JSON_KEY_PATH, path);
        addIfPresent(block, CommonConstants.JSON_KEY_OPERATION, operation);
        addIfPresent(block, CommonConstants.JSON_KEY_STATUS, getString(item, CommonConstants.JSON_KEY_STATUS));
        addIfPresent(block, CommonConstants.JSON_KEY_SUMMARY, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_SUMMARY),
                joinSummary(CommonConstants.BLOCK_TITLE_FILE_CHANGE, operation, path)
        ));
        addDetails(block, item);
        return block;
    }

    private JsonObject mcpToolCallBlock(JsonObject item) {
        JsonObject block = baseBlock(CommonConstants.BLOCK_TYPE_MCP_TOOL_CALL, CommonConstants.BLOCK_TITLE_MCP_TOOL_CALL);
        String server = getString(item, CommonConstants.JSON_KEY_SERVER);
        String tool = getString(item, CommonConstants.JSON_KEY_TOOL);
        String title = joinToolName(server, tool);
        addIfPresent(block, CommonConstants.JSON_KEY_SERVER, server);
        addIfPresent(block, CommonConstants.JSON_KEY_TOOL, tool);
        addIfPresent(block, CommonConstants.JSON_KEY_TITLE, firstNonBlank(getString(item, CommonConstants.JSON_KEY_TITLE), title));
        addIfPresent(block, CommonConstants.JSON_KEY_STATUS, getString(item, CommonConstants.JSON_KEY_STATUS));
        addIfPresent(block, CommonConstants.JSON_KEY_SUMMARY, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_SUMMARY),
                joinSummary(CommonConstants.BLOCK_TITLE_MCP_TOOL_CALL, null, title)
        ));
        addElementIfPresent(block, CommonConstants.JSON_KEY_INPUT, item.get(CliConstants.CODEX_FIELD_ARGUMENTS));
        addElementIfPresent(block, CommonConstants.JSON_KEY_RESULT, firstElement(item,
                CommonConstants.JSON_KEY_RESULT,
                CommonConstants.JSON_KEY_CONTENT,
                CommonConstants.JSON_KEY_ERROR
        ));
        addDetails(block, item);
        return block;
    }

    private JsonObject webSearchBlock(JsonObject item) {
        JsonObject block = baseBlock(CommonConstants.BLOCK_TYPE_WEB_SEARCH, CommonConstants.BLOCK_TITLE_WEB_SEARCH);
        addIfPresent(block, CommonConstants.JSON_KEY_QUERY, getString(item, CommonConstants.JSON_KEY_QUERY));
        addIfPresent(block, CommonConstants.JSON_KEY_URL, getString(item, CommonConstants.JSON_KEY_URL));
        addIfPresent(block, CommonConstants.JSON_KEY_TITLE, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_TITLE),
                getString(item, CommonConstants.JSON_KEY_URL),
                CommonConstants.BLOCK_TITLE_WEB_SEARCH
        ));
        addIfPresent(block, CommonConstants.JSON_KEY_STATUS, getString(item, CommonConstants.JSON_KEY_STATUS));
        addIfPresent(block, CommonConstants.JSON_KEY_SUMMARY, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_SUMMARY),
                joinSummary(CommonConstants.BLOCK_TITLE_WEB_SEARCH, null, firstNonBlank(
                        getString(item, CommonConstants.JSON_KEY_QUERY),
                        getString(item, CommonConstants.JSON_KEY_URL)
                ))
        ));
        addDetails(block, item);
        return block;
    }

    private JsonObject todoListBlock(JsonObject item) {
        JsonObject block = baseBlock(CommonConstants.BLOCK_TYPE_TODO_LIST, CommonConstants.BLOCK_TITLE_TODO_LIST);
        addIfPresent(block, CommonConstants.JSON_KEY_STATUS, getString(item, CommonConstants.JSON_KEY_STATUS));
        addElementIfPresent(block, CommonConstants.JSON_KEY_ITEMS, firstArray(item,
                CommonConstants.JSON_KEY_ITEMS,
                CliConstants.CODEX_FIELD_TODOS,
                CliConstants.CODEX_ITEM_TODO_LIST,
                CliConstants.CODEX_FIELD_TASKS
        ));
        addIfPresent(block, CommonConstants.JSON_KEY_SUMMARY, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_SUMMARY),
                CommonConstants.BLOCK_TITLE_TODO_LIST
        ));
        addDetails(block, item);
        return block;
    }

    private JsonObject providerErrorBlock(JsonObject item) {
        JsonObject block = baseBlock(CommonConstants.BLOCK_TYPE_PROVIDER_ERROR, CommonConstants.BLOCK_TITLE_PROVIDER_ERROR);
        block.addProperty(CommonConstants.JSON_KEY_PROVIDER, ProviderType.CODEX.value());
        addIfPresent(block, CommonConstants.JSON_KEY_STATUS, getString(item, CommonConstants.JSON_KEY_STATUS));
        addIfPresent(block, CommonConstants.JSON_KEY_SUMMARY, firstNonBlank(
                getString(item, CommonConstants.JSON_KEY_MESSAGE),
                getString(item, CommonConstants.JSON_KEY_ERROR),
                getString(item, CommonConstants.JSON_KEY_SUMMARY),
                CommonConstants.BLOCK_TITLE_PROVIDER_ERROR
        ));
        addDetails(block, item);
        return block;
    }

    private JsonObject baseBlock(String type, String title) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, type);
        block.addProperty(CommonConstants.JSON_KEY_TITLE, title);
        return block;
    }

    private void addDetails(JsonObject block, JsonObject item) {
        JsonObject sanitized = unknownProviderEventBuilder.sanitize(item);
        if (sanitized.size() > 0) {
            block.addProperty(CommonConstants.JSON_KEY_DETAILS, sanitized.toString());
        }
    }

    private void addIfPresent(JsonObject object, String key, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(key, value);
        }
    }

    private void addElementIfPresent(JsonObject object, String key, JsonElement element) {
        JsonElement sanitized = sanitizeElement(element);
        if (sanitized != null && !sanitized.isJsonNull()) {
            object.add(key, sanitized);
        }
    }

    private JsonElement sanitizeElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add(CommonConstants.JSON_KEY_VALUE, element);
        JsonObject sanitized = unknownProviderEventBuilder.sanitize(wrapper);
        return sanitized.get(CommonConstants.JSON_KEY_VALUE);
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static JsonElement firstElement(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && object.has(key)) {
                return object.get(key);
            }
        }
        return null;
    }

    private static JsonArray firstArray(JsonObject object, String... keys) {
        JsonElement element = firstElement(object, keys);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String joinToolName(String server, String tool) {
        if (server != null && !server.isBlank() && tool != null && !tool.isBlank()) {
            return server + "." + tool;
        }
        return firstNonBlank(tool, server);
    }

    private static String joinSummary(String prefix, String action, String target) {
        String suffix = firstNonBlank(action, target);
        if (suffix == null) {
            return prefix;
        }
        if (action != null && !action.isBlank() && target != null && !target.isBlank()) {
            suffix = action + CommonConstants.BLOCK_SUMMARY_SEPARATOR + target;
        }
        return prefix + CommonConstants.BLOCK_SUMMARY_SEPARATOR + suffix;
    }
}
