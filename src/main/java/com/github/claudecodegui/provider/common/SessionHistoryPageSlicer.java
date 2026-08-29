package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.protocol.payload.CodexHistoryPageInfoPayloadField;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 磁盘历史分页切片的单点实现(provider 无关)。
 *
 * <p>切片语义(轮边界 = human user 消息;pageInfo 字段复用
 * {@link CodexHistoryPageInfoPayloadField},前端 MessageList 经
 * {@code HISTORY_CODEX_PAGE_INFO} 事件统一消费)此前在 {@code CodexHistoryPageService} 与
 * {@code ClaudeHistoryPageService} 各复制一份,第三/四/五家接入时按本类单点收敛——
 * 两个既有 service 已改为委托本类。
 *
 * <p>轮边界判定对前端 Claude 兼容消息形状通用(含 grok/kimi/pi 经
 * {@code NativeCliHistoryMessages} 产出的形状):human user = type=user 且
 * (raw.content 数组含 text/image 块,或 content 字符串非 tool_result 占位);
 * tool_result 承载消息(raw.content 纯 tool_result 块)不算轮边界。
 */
public final class SessionHistoryPageSlicer {

    private SessionHistoryPageSlicer() {
    }

    /**
     * 切出一页历史。
     *
     * @param allMessages 全量前端格式消息(reader 全量读后内存切片,与 Codex/Claude 现状一致)
     * @param sessionId   会话 ID(pageInfo 回填)
     * @param beforeTurn  null=初始页(取最近 pageSize 轮,REPLACE);否则取该轮之前的页(PREPEND)
     * @param mode        分页模式(仅写入 pageInfo.mode)
     * @param pageSize    每页轮数(调用方 clamp 到 ≥1)
     */
    public static SessionHistoryLoadResult slice(List<JsonObject> allMessages, String sessionId,
                                                 Integer beforeTurn, CodexHistoryPageMode mode, int pageSize) {
        List<JsonObject> messages = allMessages == null ? List.of() : allMessages;
        List<Integer> userTurnIndexes = humanUserMessageIndexes(messages);
        int totalTurns = userTurnIndexes.size();
        int requestedToTurn = beforeTurn == null ? totalTurns : beforeTurn;
        int toTurn = clamp(requestedToTurn, 0, totalTurns);
        int fromTurn = Math.max(0, toTurn - pageSize);
        List<JsonObject> pageMessages = new ArrayList<>();
        if (beforeTurn == null && totalTurns == 0) {
            // 无 human turn 的退化场景:初始页原样返回全部,避免空会话显示为空。
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
        return new SessionHistoryLoadResult(pageMessages, info);
    }

    /** 消息是否为 human user 轮边界(消息形状契约见类注释)。 */
    public static boolean isHumanUserMessage(JsonObject message) {
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

    private static List<Integer> humanUserMessageIndexes(List<JsonObject> messages) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (isHumanUserMessage(messages.get(i))) {
                indexes.add(i);
            }
        }
        return indexes;
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
