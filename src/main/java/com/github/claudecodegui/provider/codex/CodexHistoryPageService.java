package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.protocol.payload.CodexHistoryPageInfoPayloadField;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Backend SSOT for Codex persisted-history pagination. */
public class CodexHistoryPageService {
    private final CodexHistoryPageReader reader;
    private final int pageSize;

    public CodexHistoryPageService(CodexHistoryPageReader reader) {
        this(reader, CommonConstants.CODEX_HISTORY_PAGE_SIZE);
    }

    CodexHistoryPageService(CodexHistoryPageReader reader, int pageSize) {
        if (reader == null) {
            throw new IllegalArgumentException("reader is required");
        }
        this.reader = reader;
        this.pageSize = Math.max(1, pageSize);
    }

    public CodexHistoryPageResult loadInitialPage(String sessionId) {
        return slice(reader.readFrontendMessages(sessionId), sessionId, null, CodexHistoryPageMode.REPLACE);
    }

    public CodexHistoryPageResult loadEarlierPage(String sessionId, Integer beforeTurn) {
        return slice(reader.readFrontendMessages(sessionId), sessionId, beforeTurn, CodexHistoryPageMode.PREPEND);
    }

    CodexHistoryPageResult slice(List<JsonObject> allMessages, String sessionId, Integer beforeTurn,
                                 CodexHistoryPageMode mode) {
        List<JsonObject> messages = allMessages == null ? List.of() : allMessages;
        List<Integer> userTurnIndexes = humanUserMessageIndexes(messages);
        int totalTurns = userTurnIndexes.size();
        int requestedToTurn = beforeTurn == null ? totalTurns : beforeTurn;
        int toTurn = clamp(requestedToTurn, 0, totalTurns);
        int fromTurn = Math.max(0, toTurn - pageSize);
        List<JsonObject> pageMessages = new ArrayList<>();
        if (beforeTurn == null && totalTurns == 0) {
            pageMessages.addAll(messages);
        } else if (toTurn > 0) {
            int startIndex = fromTurn == 0 ? 0 : userTurnIndexes.get(fromTurn);
            int endIndex = toTurn >= totalTurns ? messages.size() : userTurnIndexes.get(toTurn);
            for (int i = startIndex; i < endIndex; i++) {
                pageMessages.add(messages.get(i));
            }
        }

        JsonObject info = new JsonObject();
        info.addProperty(CodexHistoryPageInfoPayloadField.PAGE_ID.wireKey(), UUID.randomUUID().toString());
        info.addProperty(CodexHistoryPageInfoPayloadField.SESSION_ID.wireKey(), sessionId);
        info.addProperty(CodexHistoryPageInfoPayloadField.MODE.wireKey(), mode.value());
        info.addProperty(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey(), fromTurn);
        info.addProperty(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey(), toTurn);
        info.addProperty(CodexHistoryPageInfoPayloadField.TOTAL_TURNS.wireKey(), totalTurns);
        info.addProperty(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey(), fromTurn > 0);
        info.addProperty(CodexHistoryPageInfoPayloadField.LOADED_MESSAGE_COUNT.wireKey(), pageMessages.size());
        info.addProperty(CodexHistoryPageInfoPayloadField.CURSOR_RESET.wireKey(),
                beforeTurn != null && requestedToTurn != toTurn);
        return new CodexHistoryPageResult(pageMessages, info);
    }

    private static List<Integer> humanUserMessageIndexes(List<JsonObject> messages) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (isHumanUserMessage(messages.get(i))) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    static boolean isHumanUserMessage(JsonObject message) {
        if (message == null || !hasStringValue(message, CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_USER)) {
            return false;
        }
        JsonElement rawContent = rawContent(message);
        if (rawContent != null && rawContent.isJsonArray()) {
            JsonArray blocks = rawContent.getAsJsonArray();
            for (JsonElement element : blocks) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (hasStringValue(block, CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TEXT)
                        || hasStringValue(block, CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_IMAGE)) {
                    return true;
                }
            }
            return false;
        }
        JsonElement content = message.get(CommonConstants.JSON_KEY_CONTENT);
        if (content == null || content.isJsonNull()) {
            return false;
        }
        String value = content.isJsonPrimitive() ? content.getAsString() : content.toString();
        return !CommonConstants.TOOL_RESULT_PLACEHOLDER.equals(value);
    }

    private static JsonElement rawContent(JsonObject message) {
        JsonElement rawElement = message.get(CommonConstants.JSON_KEY_RAW);
        if (rawElement == null || !rawElement.isJsonObject()) {
            return null;
        }
        JsonObject raw = rawElement.getAsJsonObject();
        JsonElement direct = raw.get(CommonConstants.JSON_KEY_CONTENT);
        if (direct != null) {
            return direct;
        }
        JsonElement nestedMessage = raw.get(CommonConstants.JSON_KEY_MESSAGE);
        if (nestedMessage != null && nestedMessage.isJsonObject()) {
            return nestedMessage.getAsJsonObject().get(CommonConstants.JSON_KEY_CONTENT);
        }
        return null;
    }

    private static boolean hasStringValue(JsonObject object, String key, String expected) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && expected.equals(element.getAsString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
