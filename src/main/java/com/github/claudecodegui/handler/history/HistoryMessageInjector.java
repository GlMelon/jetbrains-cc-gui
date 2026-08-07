package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.CodexMessageConverter;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.util.AttachmentStorageService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for loading session messages and injecting them into the frontend.
 * Handles both Claude and Codex session loading.
 */
public class HistoryMessageInjector {

    private static final Logger LOG = Logger.getInstance(HistoryMessageInjector.class);

    /**
     * Maximum gap between two adjacent Codex records that are still treated as
     * the same SDK double-write. 500 ms comfortably covers the small jitter
     * between the rollout's response_item and event_msg entries while leaving
     * real back-to-back messages alone.
     */
    private static final long DUPLICATE_CODEX_RECORD_WINDOW_MILLIS = 500L;

    private final HandlerContext context;

    HistoryMessageInjector(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load a history session.
     */
    void handleLoadSession(String sessionId, String currentProvider, HistoryActionHandlers.SessionLoadCallback sessionLoadCallback) {
        String provider = currentProvider;
        String resolvedSessionId = sessionId;

        try {
            JsonObject payload = GsonHolder.GSON.fromJson(sessionId, JsonObject.class);
            if (payload != null) {
                if (payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                    resolvedSessionId = payload.get("sessionId").getAsString();
                }
                if (payload.has("provider") && !payload.get("provider").isJsonNull()) {
                    provider = payload.get("provider").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Backward compatible: legacy payload is the raw sessionId string.
        }

        String rawPath = context.resolveEffectiveWorkingDirectory();
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        String projectPath = NodeDetector.isWslPath(nodePath) ? NodeDetector.convertToWslPath(rawPath) : rawPath;
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null");
            return;
        }
        LOG.info("[HistoryHandler] Loading history session: " + resolvedSessionId
                + " from project: " + projectPath + ", provider: " + provider);

        if (sessionLoadCallback != null) {
            sessionLoadCallback.onLoadSession(resolvedSessionId, projectPath, provider);
        } else {
            LOG.warn("[HistoryHandler] WARNING: No session load callback set");
        }
    }

    /**
     * 将 Codex 历史消息批量转换为前端消息列表。
     * 只统一前端注入协议，不改变 Codex 历史文件格式与标题数据来源。
     */
    public static List<JsonObject> convertCodexMessagesToFrontendBatch(JsonArray messages) {
        List<JsonObject> frontendMessages = new ArrayList<>();
        CodexMessageStreamConverter converter = new CodexMessageStreamConverter();
        CodexMessageSink sink = new CodexMessageSink() {
            @Override
            public void append(JsonObject message) {
                frontendMessages.add(message);
            }

            @Override
            public void replaceLast(JsonObject message) {
                frontendMessages.set(frontendMessages.size() - 1, message);
            }
        };
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isJsonObject()) {
                converter.accept(messages.get(i).getAsJsonObject(), sink);
            }
        }
        return frontendMessages;
    }

    interface CodexMessageSink {
        void append(JsonObject message);

        void replaceLast(JsonObject message);
    }

    static final class CodexMessageStreamConverter {
        private final Set<String> emittedCliToolUseIds = new HashSet<>();
        private JsonObject previous;
        private JsonObject latestAssistant;

        void accept(JsonObject rawMessage, CodexMessageSink sink) {
            JsonObject tokenCountUsage = extractCodexTokenCountUsage(rawMessage);
            if (tokenCountUsage != null) {
                attachUsageToLatestAssistant(tokenCountUsage);
                return;
            }
            List<JsonObject> convertedMessages = convertCodexMessageToFrontendMessages(
                    rawMessage,
                    emittedCliToolUseIds
            );
            for (JsonObject incoming : convertedMessages) {
                if (previous == null) {
                    sink.append(incoming);
                    previous = incoming;
                    rememberLatestAssistant(incoming);
                    continue;
                }
                if (isDuplicateAdjacentCodexUserMessage(previous, incoming)) {
                    previous = preferRicherUserMessage(previous, incoming);
                    sink.replaceLast(previous);
                    continue;
                }
                if (isDuplicateAdjacentCodexThinkingMessage(previous, incoming)) {
                    continue;
                }
                sink.append(incoming);
                previous = incoming;
                rememberLatestAssistant(incoming);
            }
        }

        private void rememberLatestAssistant(JsonObject incoming) {
            if (incoming != null
                    && CommonConstants.MSG_TYPE_ASSISTANT.equals(getStringProperty(incoming, CommonConstants.JSON_KEY_TYPE))) {
                latestAssistant = incoming;
            }
        }

        private void attachUsageToLatestAssistant(JsonObject usage) {
            if (latestAssistant == null || usage == null) {
                return;
            }
            JsonObject raw;
            if (latestAssistant.has("raw") && latestAssistant.get("raw").isJsonObject()) {
                raw = latestAssistant.getAsJsonObject("raw");
            } else {
                raw = new JsonObject();
                latestAssistant.add("raw", raw);
            }
            raw.add("usage", usage.deepCopy());
        }
    }

    /**
     * 从 JSONL token_count 记录提取 provider 上报的 Codex 上下文快照。仅 last_token_usage
     * 代表活跃模型上下文;会话累积的 total_token_usage 被刻意忽略(可能超过模型窗口)。
     * 与实时流 {@code CodexMessageHandler#handleEventMessage} 对称,且不作为消息 emit(不计入
     * conversation messageCount),仅把 usage 附加到最近的 assistant 消息。
     */
    private static JsonObject extractCodexTokenCountUsage(JsonObject message) {
        if (message == null
                || !CliConstants.CODEX_MSG_EVENT_MSG.equals(getStringProperty(message, CommonConstants.JSON_KEY_TYPE))
                || !message.has("payload")
                || !message.get("payload").isJsonObject()) {
            return null;
        }
        JsonObject payload = message.getAsJsonObject("payload");
        if (!CliConstants.CODEX_MSG_TOKEN_COUNT.equals(getStringProperty(payload, CommonConstants.JSON_KEY_TYPE))
                || !payload.has("info")
                || !payload.get("info").isJsonObject()) {
            return null;
        }
        JsonObject info = payload.getAsJsonObject("info");
        if (!info.has("last_token_usage") || !info.get("last_token_usage").isJsonObject()) {
            return null;
        }
        JsonObject contextUsage = info.getAsJsonObject("last_token_usage");

        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", getIntProperty(contextUsage, "input_tokens"));
        usage.addProperty("output_tokens", getIntProperty(contextUsage, "output_tokens"));
        usage.addProperty("cache_read_input_tokens", getIntProperty(contextUsage, "cached_input_tokens"));
        usage.addProperty("cache_creation_input_tokens", 0);
        int contextWindow = getIntProperty(info, "model_context_window");
        if (contextWindow > 0) {
            usage.addProperty("model_context_window", contextWindow);
        }
        return usage;
    }

    private static int getIntProperty(JsonObject object, String propertyName) {
        if (object == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return 0;
        }
        try {
            return Math.max(0, object.get(propertyName).getAsInt());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean isDuplicateAdjacentCodexThinkingMessage(JsonObject previous, JsonObject incoming) {
        String previousThinking = extractThinkingText(previous);
        String incomingThinking = extractThinkingText(incoming);
        if (previousThinking == null || incomingThinking == null) {
            return false;
        }
        if (!previousThinking.trim().equals(incomingThinking.trim())) {
            return false;
        }
        return timestampsWithinWindow(previous, incoming, DUPLICATE_CODEX_RECORD_WINDOW_MILLIS);
    }

    private static String extractThinkingText(JsonObject message) {
        if (!CommonConstants.MSG_TYPE_ASSISTANT.equals(getStringProperty(message, CommonConstants.JSON_KEY_TYPE))) {
            return null;
        }
        JsonArray contentBlocks = extractRawContentBlocks(message);
        if (contentBlocks == null) {
            return null;
        }
        for (JsonElement element : contentBlocks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (!CommonConstants.BLOCK_TYPE_THINKING.equals(getStringProperty(block, CommonConstants.JSON_KEY_TYPE))) {
                continue;
            }
            return firstNonBlank(
                    getString(block, CommonConstants.JSON_KEY_THINKING),
                    getString(block, CommonConstants.JSON_KEY_TEXT)
            );
        }
        return null;
    }

    private static boolean isDuplicateAdjacentCodexUserMessage(JsonObject previous, JsonObject incoming) {
        if (!isUserMessage(previous) || !isUserMessage(incoming)) {
            return false;
        }

        // tool_result entries are one-to-one with their originating tool_use via
        // `tool_use_id`; the SDK never double-writes a single tool_result, and two
        // batch-run outputs returning within the dedup time window have identical
        // placeholder content ("[tool_result]"). Treating them as duplicates would
        // drop the trailing tool_result and leave its tool_use stuck on the
        // pending spinner. Skip dedup whenever either side carries a tool_result.
        if (hasToolResultContentBlock(previous) || hasToolResultContentBlock(incoming)) {
            return false;
        }

        String previousContent = getStringProperty(previous, "content");
        String incomingContent = getStringProperty(incoming, "content");
        if (previousContent == null
            || !normalizeDuplicateUserContent(previousContent).equals(normalizeDuplicateUserContent(incomingContent))) {
            return false;
        }

        // Codex SDK writes the same user turn twice into rollout: a `response_item`
        // whose text is wrapped with `<image name=[Image #N]>...</image>`, and an
        // `event_msg` whose `local_images` carries the real image path. When either
        // record exposes an image signal (text marker or content block), they are
        // mirror writes of the same turn and should be deduplicated regardless of
        // whether their timestamps line up to the millisecond.
        if (hasInlineImageMarker(previousContent) || hasInlineImageMarker(incomingContent)
            || hasImageContentBlock(previous) || hasImageContentBlock(incoming)) {
            return true;
        }

        // Otherwise fall back to a tight timestamp window so we still catch SDK
        // double-writes for text-only turns without accidentally merging two
        // identical user messages typed seconds apart.
        return timestampsWithinWindow(previous, incoming, DUPLICATE_CODEX_RECORD_WINDOW_MILLIS);
    }

    private static JsonObject preferRicherUserMessage(JsonObject previous, JsonObject incoming) {
        // Prefer the variant that carries an actual image content block so the
        // history view shows the rendered image rather than the `<image>` wrapper.
        boolean previousHasImage = hasImageContentBlock(previous);
        boolean incomingHasImage = hasImageContentBlock(incoming);
        if (previousHasImage != incomingHasImage) {
            return previousHasImage ? previous : incoming;
        }
        return getRawContentBlockCount(incoming) > getRawContentBlockCount(previous) ? incoming : previous;
    }

    private static boolean hasInlineImageMarker(String content) {
        return content != null && content.contains("<image");
    }

    private static boolean hasImageContentBlock(JsonObject message) {
        JsonArray contentBlocks = extractRawContentBlocks(message);
        if (contentBlocks == null) {
            return false;
        }
        for (JsonElement element : contentBlocks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (block.has("type") && CommonConstants.BLOCK_TYPE_IMAGE.equals(block.get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToolResultContentBlock(JsonObject message) {
        JsonArray contentBlocks = extractRawContentBlocks(message);
        if (contentBlocks == null) {
            return false;
        }
        for (JsonElement element : contentBlocks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (block.has("type") && CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(block.get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray extractRawContentBlocks(JsonObject message) {
        if (message == null || !message.has("raw") || !message.get("raw").isJsonObject()) {
            return null;
        }
        JsonObject raw = message.getAsJsonObject("raw");
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content");
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            if (rawMessage.has("content") && rawMessage.get("content").isJsonArray()) {
                return rawMessage.getAsJsonArray("content");
            }
        }
        return null;
    }

    private static boolean timestampsWithinWindow(JsonObject previous, JsonObject incoming, long windowMillis) {
        String prev = getStringProperty(previous, "timestamp");
        String curr = getStringProperty(incoming, "timestamp");
        if (prev == null || curr == null) {
            return false;
        }
        try {
            long prevMillis = java.time.Instant.parse(prev).toEpochMilli();
            long currMillis = java.time.Instant.parse(curr).toEpochMilli();
            return Math.abs(currMillis - prevMillis) <= windowMillis;
        } catch (Exception ignored) {
            return prev.equals(curr);
        }
    }

    private static String normalizeDuplicateUserContent(String content) {
        if (content == null) {
            return "";
        }
        return content
            .replaceAll("(?m)^<image[^\\r\\n]*>\\R?", "")
            .replaceAll("(?m)^</image>\\R?", "")
            .trim();
    }

    private static boolean isUserMessage(JsonObject message) {
        return CommonConstants.MSG_TYPE_USER.equals(getStringProperty(message, "type"));
    }

    private static String getStringProperty(JsonObject object, String propertyName) {
        if (object == null
                || !object.has(propertyName)
                || object.get(propertyName).isJsonNull()
                || !object.get(propertyName).isJsonPrimitive()) {
            return null;
        }
        return object.get(propertyName).getAsString();
    }

    private static int getRawContentBlockCount(JsonObject message) {
        if (message == null || !message.has("raw") || !message.get("raw").isJsonObject()) {
            return 0;
        }

        JsonObject raw = message.getAsJsonObject("raw");
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content").size();
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            if (rawMessage.has("content") && rawMessage.get("content").isJsonArray()) {
                return rawMessage.getAsJsonArray("content").size();
            }
        }
        return 0;
    }

    /**
     * 将 Codex 历史消息恢复到后端 SessionState，保证历史加载后继续发送时，
     * 后端内存态与前端显示态使用同一份消息基线。
     */
    static void restoreCodexMessagesToSessionState(SessionState state, JsonArray messages) {
        restoreCodexFrontendMessagesToSessionState(
                state, convertCodexMessagesToFrontendBatch(messages));
    }

    private static void restoreCodexFrontendMessagesToSessionState(SessionState state,
                                                                    List<JsonObject> frontendMessages) {
        state.clearMessages();
        for (JsonObject frontendMsg : frontendMessages) {
            ClaudeSession.Message restoredMessage = toSessionMessage(frontendMsg);
            if (restoredMessage != null) {
                state.addMessage(restoredMessage);
            }
        }
    }

    private static boolean isHumanUserMessage(JsonObject message) {
        if (!isUserMessage(message)) {
            return false;
        }
        if (!message.has("raw") || !message.get("raw").isJsonObject()) {
            return !"[tool_result]".equals(getStringProperty(message, "content"));
        }

        JsonObject raw = message.getAsJsonObject("raw");
        if (!raw.has("content") || !raw.get("content").isJsonArray()) {
            return !"[tool_result]".equals(getStringProperty(message, "content"));
        }
        for (JsonElement blockElement : raw.getAsJsonArray("content")) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            String blockType = getStringProperty(blockElement.getAsJsonObject(), "type");
            if ("text".equals(blockType) || "image".equals(blockType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将前端统一消息结构恢复为会话内存消息结构。
     */
    static ClaudeSession.Message toSessionMessage(JsonObject frontendMsg) {
        if (frontendMsg == null || !frontendMsg.has("type")) {
            return null;
        }

        String type = frontendMsg.get("type").getAsString();
        ClaudeSession.Message.Type messageType;
        switch (type) {
            case "user":
                messageType = ClaudeSession.Message.Type.USER;
                break;
            case "assistant":
                messageType = ClaudeSession.Message.Type.ASSISTANT;
                break;
            case "system":
                messageType = ClaudeSession.Message.Type.SYSTEM;
                break;
            case "error":
                messageType = ClaudeSession.Message.Type.ERROR;
                break;
            default:
                return null;
        }

        String content = frontendMsg.has("content") ? frontendMsg.get("content").getAsString() : "";
        JsonObject raw = frontendMsg.has("raw") && frontendMsg.get("raw").isJsonObject()
            ? frontendMsg.getAsJsonObject("raw")
            : null;
        ClaudeSession.Message restored = raw != null
            ? new ClaudeSession.Message(messageType, content, raw.deepCopy())
            : new ClaudeSession.Message(messageType, content);
        Long sourceTimestamp = parseFrontendTimestamp(frontendMsg);
        if (sourceTimestamp != null) {
            restored.timestamp = sourceTimestamp;
        }
        return restored;
    }

    private static Long parseFrontendTimestamp(JsonObject frontendMsg) {
        if (!frontendMsg.has("timestamp") || frontendMsg.get("timestamp").isJsonNull()) {
            return null;
        }
        JsonElement timestamp = frontendMsg.get("timestamp");
        if (!timestamp.isJsonPrimitive()) {
            return null;
        }
        try {
            if (timestamp.getAsJsonPrimitive().isNumber()) {
                return timestamp.getAsLong();
            }
            String value = timestamp.getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return Instant.parse(value).toEpochMilli();
            }
        } catch (NumberFormatException | DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * 将单条 Codex 历史消息转换为前端消息。
     * Handles both event_msg (user messages) and response_item (assistant/tool messages).
     */
    public static JsonObject convertCodexMessageToFrontend(JsonObject msg) {
        List<JsonObject> messages = convertCodexMessageToFrontendMessages(msg, new HashSet<>());
        return messages.isEmpty() ? null : messages.get(0);
    }

    private static List<JsonObject> convertCodexMessageToFrontendMessages(JsonObject msg, Set<String> emittedCliToolUseIds) {
        if (!msg.has("type")) {
            return List.of();
        }

        String type = msg.get("type").getAsString();
        JsonObject payload = msg.has("payload") && msg.get("payload").isJsonObject()
                ? msg.getAsJsonObject("payload") : null;
        String timestamp = msg.has("timestamp") ? msg.get("timestamp").getAsString() : null;

        if (type.startsWith("item.")) {
            JsonObject item = msg.has("item") && msg.get("item").isJsonObject()
                    ? msg.getAsJsonObject("item") : null;
            return convertCliItemToFrontendMessages(type, item, timestamp, emittedCliToolUseIds);
        }

        if (payload == null) {
            return List.of();
        }

        if (CliConstants.CODEX_MSG_PROVIDER_ERROR.equals(type)) {
            JsonObject converted = convertProviderErrorToFrontend(payload, timestamp);
            return converted == null ? List.of() : List.of(converted);
        }

        // Handle event_msg containing user_message or agent_reasoning
        if (CliConstants.CODEX_MSG_EVENT_MSG.equals(type)) {
            String payloadType = getString(payload, "type");
            if (CliConstants.CODEX_PAYLOAD_AGENT_REASONING.equals(payloadType)) {
                String text = getString(payload, "text");
                return text == null || text.isBlank()
                        ? List.of()
                        : List.of(createThinkingAssistantMessage(text, timestamp));
            }
            JsonObject converted = convertEventMsgToFrontend(payload, timestamp);
            return converted == null ? List.of() : List.of(converted);
        }

        // Handle response_item (assistant messages, function calls, etc.)
        if (CliConstants.CODEX_EVENT_RESPONSE_ITEM.equals(type)) {
            if (!payload.has("type")) {
                return List.of();
            }
            String payloadType = payload.get("type").getAsString();

            JsonObject converted = null;
            if (CliConstants.CODEX_PAYLOAD_MESSAGE.equals(payloadType)) {
                converted = CodexMessageConverter.convertCodexMessageToFrontend(payload, timestamp);
            } else if (CliConstants.CODEX_PAYLOAD_REASONING.equals(payloadType)) {
                String text = extractReasoningText(payload);
                converted = text == null || text.isBlank() ? null : createThinkingAssistantMessage(text, timestamp);
            } else if (CliConstants.CODEX_PAYLOAD_FUNCTION_CALL.equals(payloadType)) {
                converted = CodexMessageConverter.convertFunctionCallToToolUse(payload, timestamp);
            } else if (CliConstants.CODEX_PAYLOAD_FUNCTION_CALL_OUTPUT.equals(payloadType)) {
                converted = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, timestamp);
            } else if (CliConstants.CODEX_PAYLOAD_CUSTOM_TOOL_CALL.equals(payloadType)) {
                converted = CodexMessageConverter.convertCustomToolCallToToolUse(payload, timestamp);
            }
            return converted == null ? List.of() : List.of(converted);
        }

        return List.of();
    }

    private static String extractReasoningText(JsonObject payload) {
        String directText = firstNonBlank(
                getString(payload, "text"),
                getString(payload, "content")
        );
        if (directText != null && !directText.isBlank()) {
            return directText;
        }

        if (payload == null || !payload.has("summary") || payload.get("summary").isJsonNull()) {
            return null;
        }
        return extractReasoningSummaryText(payload.get("summary"));
    }

    private static String extractReasoningSummaryText(JsonElement summary) {
        if (summary == null || summary.isJsonNull()) {
            return null;
        }
        if (summary.isJsonPrimitive()) {
            return summary.getAsString();
        }
        if (summary.isJsonObject()) {
            JsonObject summaryObject = summary.getAsJsonObject();
            return firstNonBlank(getString(summaryObject, "text"), getString(summaryObject, "content"));
        }
        if (!summary.isJsonArray()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (JsonElement element : summary.getAsJsonArray()) {
            String text = extractReasoningSummaryText(element);
            if (text != null && !text.isBlank()) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static JsonObject convertProviderErrorToFrontend(JsonObject payload, String timestamp) {
        String details = firstNonBlank(getString(payload, "details"), getString(payload, "message"), getString(payload, "error"));
        String summary = firstNonBlank(getString(payload, "summary"), summarizeProviderError(details));
        if (details == null || details.isBlank()) {
            details = summary;
        }
        if (summary == null || summary.isBlank()) {
            summary = "Codex 响应失败";
        }

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");
        frontendMsg.addProperty("content", summary);

        JsonObject errorBlock = new JsonObject();
        errorBlock.addProperty("type", "provider_error");
        errorBlock.addProperty("provider", firstNonBlank(getString(payload, "provider"), CommonConstants.PROVIDER_CODEX));
        errorBlock.addProperty("summary", summary);
        errorBlock.addProperty("details", details);
        if (payload.has("exitCode") && !payload.get("exitCode").isJsonNull()) {
            errorBlock.add("exitCode", payload.get("exitCode").deepCopy());
        }
        if (payload.has("requestId") && !payload.get("requestId").isJsonNull()) {
            errorBlock.add("requestId", payload.get("requestId").deepCopy());
        }
        if (payload.has("url") && !payload.get("url").isJsonNull()) {
            errorBlock.add("url", payload.get("url").deepCopy());
        }

        JsonArray content = new JsonArray();
        content.add(errorBlock);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }
        return frontendMsg;
    }

    private static String summarizeProviderError(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        String trimmed = details.trim();
        int reasonIndex = trimmed.indexOf("原因：");
        if (reasonIndex >= 0 && reasonIndex + 3 < trimmed.length()) {
            return trimmed.substring(reasonIndex + 3).trim();
        }
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "...";
    }

    private static List<JsonObject> convertCliItemToFrontendMessages(
            String eventType,
            JsonObject item,
            String timestamp,
            Set<String> emittedToolUseIds
    ) {
        if (item == null || !item.has("type")) {
            return List.of();
        }

        String itemType = item.get("type").getAsString();
        if (CliConstants.CODEX_ITEM_AGENT_MESSAGE.equals(itemType)) {
            String text = getString(item, "text");
            if (text == null || text.isBlank()) {
                return List.of();
            }
            return List.of(createTextAssistantMessage(text, timestamp));
        }

        if (CliConstants.CODEX_ITEM_COMMAND_EXECUTION.equals(itemType)) {
            return convertCliCommandExecutionItem(eventType, item, timestamp, emittedToolUseIds);
        }

        if (CliConstants.CODEX_ITEM_MCP_TOOL_CALL.equals(itemType)) {
            return convertCliMcpToolCallItem(eventType, item, timestamp, emittedToolUseIds);
        }

        // reasoning item 转成 thinking content block(参照实时路径 CodexCliSession.handleReasoningItem
        // 与 ai-bridge emitThinkingBlock 的 {type:thinking,thinking,text} 结构)。H2:此前落此处的
        // return List.of() 静默丢弃,导致重开含思考的 Codex 会话思考区全空(实时流式正常,仅历史回放丢失)。
        if (CliConstants.CODEX_ITEM_REASONING.equals(itemType)) {
            String text = firstNonBlank(
                    getString(item, "text"),
                    getString(item, "summary"),
                    getString(item, "content")
            );
            if (text == null || text.isBlank()) {
                return List.of();
            }
            return List.of(createThinkingAssistantMessage(text, timestamp));
        }

        return List.of();
    }

    private static List<JsonObject> convertCliCommandExecutionItem(
            String eventType,
            JsonObject item,
            String timestamp,
            Set<String> emittedToolUseIds
    ) {
        String id = firstNonBlank(getString(item, "id"), getString(item, "call_id"), "command_execution");
        String command = firstNonBlank(getString(item, "command"), getString(item, "cmd"), getString(item, "program"), "(unknown command)");
        JsonObject input = new JsonObject();
        input.addProperty("command", command);
        input.addProperty("description", commandDescription(command));

        List<JsonObject> messages = new ArrayList<>();
        addToolUseIfNeeded(messages, emittedToolUseIds, id, "Bash", input, timestamp);

        if ("item.completed".equals(eventType)) {
            messages.add(createToolResultMessage(id, isCliItemError(item), extractCliCommandOutput(item), timestamp));
        }

        return messages;
    }

    private static List<JsonObject> convertCliMcpToolCallItem(
            String eventType,
            JsonObject item,
            String timestamp,
            Set<String> emittedToolUseIds
    ) {
        String id = firstNonBlank(getString(item, "id"), getString(item, "call_id"), "mcp_tool_call");
        String toolName = normalizeMcpToolName(getString(item, "server"), getString(item, "tool"));
        JsonObject input = item.has("arguments") && item.get("arguments").isJsonObject()
                ? item.getAsJsonObject("arguments")
                : new JsonObject();

        List<JsonObject> messages = new ArrayList<>();
        addToolUseIfNeeded(messages, emittedToolUseIds, id, toolName, input, timestamp);

        if ("item.completed".equals(eventType)) {
            messages.add(createToolResultMessage(id, isCliItemError(item) || item.has("error"), extractCliMcpResult(item), timestamp));
        }

        return messages;
    }

    private static void addToolUseIfNeeded(
            List<JsonObject> messages,
            Set<String> emittedToolUseIds,
            String id,
            String name,
            JsonObject input,
            String timestamp
    ) {
        if (!emittedToolUseIds.add(id)) {
            return;
        }
        messages.add(createToolUseMessage(id, name, input, timestamp));
    }

    private static JsonObject createTextAssistantMessage(String text, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");
        frontendMsg.addProperty("content", text);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(textBlock);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }
        return frontendMsg;
    }

    /**
     * 构造承载 thinking content block 的 assistant frontendMsg(历史回放批量格式)。
     *
     * <p>结构对齐实时路径 ai-bridge {@code emitThinkingBlock} 产出的
     * {@code {type:"thinking", thinking, text}},前端 contentBlockNormalize 据此渲染思考区。
     * content 顶层字段保留 reasoning 文本(与 Claude 历史 thinking 透传语义一致),不阻断正文。
     */
    private static JsonObject createThinkingAssistantMessage(String text, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");
        frontendMsg.addProperty("content", text);

        JsonObject thinkingBlock = new JsonObject();
        thinkingBlock.addProperty("type", "thinking");
        thinkingBlock.addProperty("thinking", text);
        thinkingBlock.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(thinkingBlock);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }
        return frontendMsg;
    }

    private static JsonObject createToolUseMessage(String id, String name, JsonObject input, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");
        frontendMsg.addProperty("content", "");

        JsonObject toolUse = new JsonObject();
        toolUse.addProperty("type", "tool_use");
        toolUse.addProperty("id", id);
        toolUse.addProperty("name", name);
        toolUse.add("input", input != null ? input : new JsonObject());

        JsonArray content = new JsonArray();
        content.add(toolUse);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }
        return frontendMsg;
    }

    private static JsonObject createToolResultMessage(String toolUseId, boolean isError, String contentText, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "user");
        frontendMsg.addProperty("content", "[tool_result]");

        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", toolUseId);
        toolResult.addProperty("is_error", isError);
        toolResult.addProperty("content", contentText == null || contentText.isBlank() ? "(no output)" : contentText);

        JsonArray content = new JsonArray();
        content.add(toolResult);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "user");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }
        return frontendMsg;
    }

    private static boolean isCliItemError(JsonObject item) {
        String status = getString(item, "status");
        if (status != null && (CliConstants.CODEX_STATUS_FAILED.equalsIgnoreCase(status) || CliConstants.CODEX_STATUS_ERROR.equalsIgnoreCase(status))) {
            return true;
        }
        if (item.has("is_error") && item.get("is_error").isJsonPrimitive() && item.get("is_error").getAsBoolean()) {
            return true;
        }
        if (item.has("error") && !item.get("error").isJsonNull()) {
            return true;
        }
        if (item.has("exit_code") && item.get("exit_code").isJsonPrimitive()) {
            try {
                return item.get("exit_code").getAsInt() != 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static String extractCliCommandOutput(JsonObject item) {
        return firstNonBlank(
                getString(item, "aggregated_output"),
                getString(item, "output"),
                getString(item, "stdout"),
                getString(item, "stderr"),
                getString(item, "result"),
                "(no output)"
        );
    }

    private static String extractCliMcpResult(JsonObject item) {
        if (item.has("error") && item.get("error").isJsonObject()) {
            String message = getString(item.getAsJsonObject("error"), "message");
            if (message != null) {
                return message;
            }
        }
        if (!item.has("result") || item.get("result").isJsonNull()) {
            return "(no output)";
        }

        JsonElement result = item.get("result");
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        if (result.isJsonObject()) {
            JsonObject resultObj = result.getAsJsonObject();
            if (resultObj.has("content") && resultObj.get("content").isJsonArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonElement element : resultObj.getAsJsonArray("content")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject block = element.getAsJsonObject();
                    if (CommonConstants.BLOCK_TYPE_TEXT.equals(getString(block, "type"))) {
                        String text = getString(block, "text");
                        if (text != null && !text.isBlank()) {
                            parts.add(text);
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    return String.join("\n", parts);
                }
            }
            if (resultObj.has("structured_content")) {
                return resultObj.get("structured_content").toString();
            }
        }
        return result.toString();
    }

    private static String normalizeMcpToolName(String server, String tool) {
        String normalizedServer = server == null || server.isBlank() ? "mcp" : sanitizeToolNamePart(server);
        String normalizedTool = tool == null || tool.isBlank() ? "tool" : sanitizeToolNamePart(tool);
        return "mcp__" + normalizedServer + "__" + normalizedTool;
    }

    private static String sanitizeToolNamePart(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String commandDescription(String command) {
        if (command == null || command.isBlank()) {
            return "Run command";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("git status")) {
            return "Check git status";
        }
        if (trimmed.startsWith("git diff")) {
            return "Inspect git diff";
        }
        if (trimmed.startsWith("ls") || trimmed.contains(" Get-ChildItem")) {
            return "List files";
        }
        return "Run command";
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

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /**
     * Convert event_msg with user_message payload to frontend format.
     */
    private static JsonObject convertEventMsgToFrontend(JsonObject payload, String timestamp) {
        if (!payload.has("type") || !CliConstants.CODEX_PAYLOAD_USER_MESSAGE.equals(payload.get("type").getAsString())) {
            return null;
        }
        boolean hasLocalImages = hasLocalImages(payload);
        if (!payload.has("message") || payload.get("message").isJsonNull()) {
            if (!hasLocalImages) {
                return null;
            }
        }

        String content = "";
        if (payload.has("message") && !payload.get("message").isJsonNull()) {
            content = CodexMessageConverter.stripSystemTags(payload.get("message").getAsString());
        }
        if ((content == null || content.isBlank()) && !hasLocalImages) {
            return null;
        }
        if (content == null) {
            content = "";
        }

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "user");
        frontendMsg.addProperty("content", content);

        // Build raw structure compatible with MessageParser
        JsonObject rawObj = new JsonObject();
        JsonArray contentBlocks = buildUserMessageContentBlocks(payload, content);
        rawObj.add("content", contentBlocks);
        rawObj.addProperty("role", "user");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    private static JsonArray buildUserMessageContentBlocks(JsonObject payload, String content) {
        JsonArray contentBlocks = new JsonArray();
        appendLocalImageBlocks(payload, contentBlocks);

        if (content != null && !content.isBlank()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", content);
            contentBlocks.add(textBlock);
        }
        return contentBlocks;
    }

    private static boolean hasLocalImages(JsonObject payload) {
        return payload.has("local_images")
            && payload.get("local_images").isJsonArray()
            && payload.getAsJsonArray("local_images").size() > 0;
    }

    private static void appendLocalImageBlocks(JsonObject payload, JsonArray contentBlocks) {
        if (!payload.has("local_images") || !payload.get("local_images").isJsonArray()) {
            return;
        }

        JsonArray localImages = payload.getAsJsonArray("local_images");
        for (JsonElement imageElement : localImages) {
            if (!imageElement.isJsonPrimitive()) {
                continue;
            }
            String imagePath = imageElement.getAsString();
            JsonObject imageBlock = createLocalImageBlock(imagePath);
            if (imageBlock != null) {
                contentBlocks.add(imageBlock);
            }
        }
    }

    private static JsonObject createLocalImageBlock(String imagePath) {
        JsonObject imageBlock = AttachmentStorageService.getInstance().createImageBlockFromPath(imagePath);
        if (imageBlock == null && imagePath != null && !imagePath.isBlank()) {
            LOG.debug("[HistoryMessageInjector] Skip missing local image: " + imagePath);
        }
        return imageBlock;
    }

    /** Target character budget for a single history batch payload (upstream chunking). */
    static final int HISTORY_BATCH_TARGET_CHAR_LIMIT = 180_000;

    /**
     * Split a history payload string into chunks no larger than {@link #HISTORY_BATCH_TARGET_CHAR_LIMIT}
     * when measured in escaped-JS characters. Used by SubagentHistoryService to stream large
     * history batches to the frontend in chunks.
     */
    static List<String> splitHistoryPayload(String payload) {
        List<String> chunks = new ArrayList<>();
        if (payload == null || payload.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder(HISTORY_BATCH_TARGET_CHAR_LIMIT);
        int escapedChars = 0;
        for (int i = 0; i < payload.length(); i++) {
            char value = payload.charAt(i);
            int charCount = escapedCharCount(current, value);
            boolean surrogatePair = Character.isHighSurrogate(value)
                    && i + 1 < payload.length()
                    && Character.isLowSurrogate(payload.charAt(i + 1));
            if (surrogatePair) {
                charCount += 1;
            }

            if (escapedChars + charCount > HISTORY_BATCH_TARGET_CHAR_LIMIT && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                escapedChars = 0;
                charCount = escapedCharCount(current, value) + (surrogatePair ? 1 : 0);
            }

            current.append(value);
            if (surrogatePair) {
                current.append(payload.charAt(++i));
            }
            escapedChars += charCount;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static int escapedCharCount(StringBuilder current, char value) {
        if (value == '' || value == ' ' || value == ' ') {
            return 6;
        }
        if (value == '\\' || value == '\'' || value == '"' || value == '`'
                || value == '\n' || value == '\r' || value == '\t'
                || value == '\b' || value == '\f' || value == '\0') {
            return 2;
        }
        if (value == '/' && current.length() > 0 && current.charAt(current.length() - 1) == '<') {
            return 2;
        }
        return 1;
    }

}
