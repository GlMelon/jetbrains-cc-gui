package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 解析 claude CLI stream-json 输出的每行 JSON，翻译为 MessageCallback 事件。
 * CLI 命令: claude -p --output-format stream-json --verbose --include-partial-messages
 *
 * 每行 JSON 格式示例:
 * - {"type":"system","subtype":"init","session_id":"..."}
 * - {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}
 * - {"type":"assistant","message":{"content":[{"type":"tool_use",...}]}}
 * - {"type":"result","usage":{...},"result":"..."}
 */
public class ClaudeCliStreamParser {

    private static final Logger LOG = Logger.getInstance(ClaudeCliStreamParser.class);

    private final Gson gson;

    // 状态跟踪，确保 stream_start/message_start 只发送一次
    private boolean streamStarted;
    private boolean messageStarted;
    private boolean thinkingActive;
    private final StringBuilder pendingTextBlock = new StringBuilder();
    private boolean textBlockAfterToolStructure;
    private boolean nextTextBlockIsAfterToolStructure;
    private boolean readToolUseSeen;
    private boolean imageReadToolUseSeen;
    private boolean imageToolResultSeen;
    private boolean imageUnderstandingObserved;
    private TextBlockRoute currentTextBlockRoute = TextBlockRoute.NONE;

    private static final String UNSUPPORTED_IMAGE_MESSAGE = "__I18N__:aiBridge.unsupportedImageVision";

    /**
     * 每次新请求前重置状态。
     */
    public void resetState() {
        streamStarted = false;
        messageStarted = false;
        thinkingActive = false;
        textBlockAfterToolStructure = false;
        nextTextBlockIsAfterToolStructure = false;
        readToolUseSeen = false;
        imageReadToolUseSeen = false;
        imageToolResultSeen = false;
        imageUnderstandingObserved = false;
        currentTextBlockRoute = TextBlockRoute.NONE;
        pendingTextBlock.setLength(0);
    }

    public ClaudeCliStreamParser(Gson gson) {
        this.gson = gson;
    }

    private void handleStreamEvent(
            JsonObject obj,
            MessageCallback callback,
            StringBuilder assistantContent,
            boolean suppressThinking
    ) {
        if (!obj.has("event")) {
            return;
        }
        JsonObject event = obj.getAsJsonObject("event");
        String eventType = event.has("type") ? event.get("type").getAsString() : "";

        switch (eventType) {
            case CliConstants.MSG_MESSAGE_START:
                textBlockAfterToolStructure = false;
                nextTextBlockIsAfterToolStructure = false;
                emitStreamStartIfNeeded(callback);
                emitMessageStartIfNeeded(callback);
                break;

            case CliConstants.STREAM_CONTENT_BLOCK_START:
                if (event.has("content_block")) {
                    JsonObject block = event.getAsJsonObject("content_block");
                    String blockType = block.has("type") ? block.get("type").getAsString() : "";
                    if (CommonConstants.BLOCK_TYPE_THINKING.equals(blockType)) {
                        callback.onMessage(CommonConstants.MSG_TYPE_THINKING, "");
                        thinkingActive = true;
                        currentTextBlockRoute = TextBlockRoute.NONE;
                    } else if (CommonConstants.BLOCK_TYPE_TEXT.equals(blockType)) {
                        currentTextBlockRoute = TextBlockRoute.UNKNOWN;
                        textBlockAfterToolStructure = nextTextBlockIsAfterToolStructure;
                        nextTextBlockIsAfterToolStructure = false;
                        pendingTextBlock.setLength(0);
                    } else {
                        currentTextBlockRoute = TextBlockRoute.NONE;
                        if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(blockType)
                                || CommonConstants.BLOCK_TYPE_SERVER_TOOL_USE.equals(blockType)) {
                            nextTextBlockIsAfterToolStructure = true;
                        }
                    }
                }
                break;

            case CliConstants.STREAM_CONTENT_BLOCK_DELTA:
                if (event.has("delta")) {
                    JsonObject delta = event.getAsJsonObject("delta");
                    String deltaType = delta.has("type") ? delta.get("type").getAsString() : "";
                    switch (deltaType) {
                        case CliConstants.DELTA_TEXT:
                            String text = delta.has("text") ? delta.get("text").getAsString() : "";
                            if (!text.isEmpty()) {
                                handleTextDelta(text, callback, assistantContent);
                            }
                            break;
                        case CliConstants.MSG_THINKING_DELTA:
                            String thinking = delta.has("thinking") ? delta.get("thinking").getAsString() : "";
                            if (!thinking.isEmpty()) {
                                callback.onMessage(CliConstants.MSG_THINKING_DELTA, thinking);
                            }
                            break;
                        case CliConstants.DELTA_INPUT_JSON:
                            // 工具调用的部分输入，暂时跳过
                            break;
                        default:
                            break;
                    }
                }
                break;

            case CliConstants.STREAM_CONTENT_BLOCK_STOP:
                flushPendingTextBlock(callback, assistantContent, false);
                if (thinkingActive) {
                    thinkingActive = false;
                }
                currentTextBlockRoute = TextBlockRoute.NONE;
                textBlockAfterToolStructure = false;
                break;

            case CliConstants.STREAM_MESSAGE_DELTA:
                // 包含 stop_reason 和 usage
                if (event.has("usage")) {
                    callback.onMessage(CliConstants.MSG_USAGE, event.get("usage").toString());
                }
                break;

            case CliConstants.STREAM_MESSAGE_STOP:
                flushPendingTextBlock(callback, assistantContent, false);
                // CLI agentic 模式下，Claude 会有多个 assistant turn（文字→工具调用→继续输出）。
                // message_stop 只是单个 turn 的结束，不是整个会话的结束。
                // 发送 message_end 清除 thinking 状态，重置 messageStarted 让下一个 turn
                // 的 message_start 能重新触发，从而在 handler 中创建新的 assistant 消息。
                callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                messageStarted = false;
                LOG.debug("[CliStreamParser] message_stop received, emitted message_end for turn boundary");
                break;

            default:
                break;
        }
    }

    /**
     * 解析一行 stream-json 输出并分发到 callback。
     */
    public void parseLine(
            String line,
            MessageCallback callback,
            SDKResult result,
            StringBuilder assistantContent,
            AtomicBoolean hadSendError,
            boolean suppressThinking
    ) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        JsonObject obj;
        try {
            obj = gson.fromJson(line.trim(), JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[CliStreamParser] 非JSON行，跳过: " + line);
            return;
        }

        if (obj == null || !obj.has("type")) {
            return;
        }

        String type = obj.get("type").getAsString();

        switch (type) {
            case CommonConstants.MSG_TYPE_SYSTEM:
                handleSystem(obj, callback);
                break;
            case CliConstants.MSG_STREAM_EVENT:
                handleStreamEvent(obj, callback, assistantContent, suppressThinking);
                break;
            case CommonConstants.MSG_TYPE_ASSISTANT:
                handleAssistant(obj, callback);
                break;
            case CommonConstants.MSG_TYPE_USER:
                handleUser(obj, callback);
                break;
            case CliConstants.MSG_RESULT:
                handleResult(obj, callback, result, assistantContent);
                break;
            default:
                LOG.debug("[CliStreamParser] 未知类型: " + type);
                break;
        }
    }

    private void handleSystem(JsonObject obj, MessageCallback callback) {
        String subtype = obj.has("subtype") ? obj.get("subtype").getAsString() : "";

        if (CliConstants.SUBTYPE_INIT.equals(subtype)) {
            // 提取 session_id
            if (obj.has("session_id")) {
                String sessionId = obj.get("session_id").getAsString();
                callback.onMessage(CliConstants.MSG_SESSION_ID,sessionId);
            }
            emitStreamStartIfNeeded(callback);
            emitMessageStartIfNeeded(callback);
        }
        // subtype="status" (如 "requesting") 跳过
    }

    private void handleAssistant(JsonObject obj, MessageCallback callback) {
        if (!obj.has("message")) {
            return;
        }
        JsonObject message = obj.getAsJsonObject("message");
        if (!message.has("content")) {
            return;
        }
        JsonArray content = message.getAsJsonArray("content");
        for (JsonElement elem : content) {
            JsonObject block = elem.getAsJsonObject();
            String blockType = block.has("type") ? block.get("type").getAsString() : "";
            if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(blockType)) {
                nextTextBlockIsAfterToolStructure = true;
                if (CliConstants.TOOL_NAME_READ.equals(getString(block, "name"))) {
                    readToolUseSeen = true;
                    if (isImagePath(getToolUseFilePath(block))) {
                        imageReadToolUseSeen = true;
                    }
                }
                callback.onMessage(CommonConstants.MSG_TYPE_TOOL_USE,block.toString());
            } else if (CommonConstants.BLOCK_TYPE_SERVER_TOOL_USE.equals(blockType)) {
                nextTextBlockIsAfterToolStructure = true;
            }
            // text 类型的内容已通过 content_block_delta 接收，跳过
        }
    }

    private void handleUser(JsonObject obj, MessageCallback callback) {
        if (obj.has("message")) {
            JsonObject message = obj.getAsJsonObject("message");
            if (message.has("content")) {
                JsonArray content = message.getAsJsonArray("content");
                for (JsonElement elem : content) {
                    JsonObject block = elem.getAsJsonObject();
                    String blockType = block.has("type") ? block.get("type").getAsString() : "";
                    if (CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(blockType)) {
                        nextTextBlockIsAfterToolStructure = true;
                        if (imageReadToolUseSeen && containsImageToolResult(block)) {
                            imageToolResultSeen = true;
                        }
                        callback.onMessage(CommonConstants.MSG_TYPE_TOOL_RESULT,block.toString());
                    }
                }
            }
        }
    }

    private void handleResult(JsonObject obj, MessageCallback callback, SDKResult result, StringBuilder assistantContent) {
        flushPendingTextBlock(callback, assistantContent, false);
        String resultText = null;

        // 提取 usage
        if (obj.has("usage")) {
            callback.onMessage(CliConstants.MSG_USAGE,obj.get("usage").toString());
        }

        boolean isErrorResult = obj.has("is_error") && obj.get("is_error").isJsonPrimitive() && obj.get("is_error").getAsBoolean();

        // 提取 result 文本（最终完整回复）
        if (!isErrorResult && obj.has("result")) {
            resultText = obj.get("result").getAsString();
            if (!resultText.isEmpty() && assistantContent.length() == 0) {
                // 没有通过 delta 收到内容时，使用 result
                assistantContent.append(resultText);
                markImageUnderstandingObserved(resultText);
                callback.onMessage(CliConstants.MSG_CONTENT,resultText);
            }
        }

        // 提取 session_id（可能和 init 中的一致）
        if (obj.has("session_id")) {
            String sessionId = obj.get("session_id").getAsString();
            callback.onMessage(CliConstants.MSG_SESSION_ID,sessionId);
        }

        // result 是 CLI 会话的最终事件，在这里发送 stream_end 和 message_end。
        // 不能在 message_stop 时发送 stream_end，因为 agentic 模式下
        // 可能有多个 assistant turn（工具调用 → 结果 → 继续输出），
        // message_stop 只是单个 turn 的结束。
        callback.onMessage(CliConstants.MSG_STREAM_END,"");
        callback.onMessage(CliConstants.MSG_MESSAGE_END,"");

        if (isErrorResult) {
            String rawError = obj.has("result") && obj.get("result").isJsonPrimitive() ? obj.get("result").getAsString() : obj.toString();
            result.success = false;
            result.error = isLikelyUnsupportedImageResult(obj, rawError)
                    ? UNSUPPORTED_IMAGE_MESSAGE + "\n\nDetails:\n" + rawError
                    : CliErrorFormatter.formatError("Claude", rawError);
            result.finalResult = null;
            return;
        }

        if (shouldReportSilentImageFailure(resultText, assistantContent.toString())) {
            result.success = false;
            result.error = UNSUPPORTED_IMAGE_MESSAGE + "\n\nDetails:\nThe model finished without any readable image-analysis output after the image tool result was returned.";
            result.finalResult = null;
            return;
        }

        result.success = true;
        result.finalResult = assistantContent.toString();
    }

    private void handleTextDelta(String text, MessageCallback callback, StringBuilder assistantContent) {
        if (currentTextBlockRoute == TextBlockRoute.TOOL_TRACE) {
            callback.onMessage(CliConstants.MSG_THINKING_DELTA,text);
            return;
        }
        if (currentTextBlockRoute == TextBlockRoute.CONTENT || currentTextBlockRoute == TextBlockRoute.NONE) {
            markImageUnderstandingObserved(text);
            emitContentDelta(text, callback, assistantContent);
            return;
        }

        pendingTextBlock.append(text);
        String candidate = pendingTextBlock.toString();
        if (isToolTraceText(candidate)) {
            currentTextBlockRoute = TextBlockRoute.TOOL_TRACE;
            pendingTextBlock.setLength(0);
            callback.onMessage(CliConstants.MSG_THINKING_DELTA,candidate);
            return;
        }
        if (!isPotentialToolTracePrefix(candidate)) {
            currentTextBlockRoute = TextBlockRoute.CONTENT;
            pendingTextBlock.setLength(0);
            emitContentDelta(candidate, callback, assistantContent);
        }
    }

    private void flushPendingTextBlock(MessageCallback callback, StringBuilder assistantContent, boolean forceToolTrace) {
        if (pendingTextBlock.length() == 0) {
            return;
        }
        String text = pendingTextBlock.toString();
        pendingTextBlock.setLength(0);
        if (forceToolTrace || currentTextBlockRoute == TextBlockRoute.TOOL_TRACE || isToolTraceText(text)) {
            callback.onMessage(CliConstants.MSG_THINKING_DELTA,text);
            return;
        }
        markImageUnderstandingObserved(text);
        emitContentDelta(text, callback, assistantContent);
    }

    private void emitContentDelta(String text, MessageCallback callback, StringBuilder assistantContent) {
        assistantContent.append(text);
        callback.onMessage(CliConstants.MSG_CONTENT_DELTA,text);
    }

    private void markImageUnderstandingObserved(String text) {
        if (!imageReadToolUseSeen || imageUnderstandingObserved || text == null) {
            return;
        }
        if (containsMeaningfulImageUnderstandingText(text)) {
            imageUnderstandingObserved = true;
        }
    }

    private boolean isToolTraceText(String text) {
        String normalized = normalizeToolTraceText(text);
        if (normalized.contains("built-in tool") || normalized.contains("executing on server")) {
            return true;
        }
        if (textBlockAfterToolStructure && startsWithToolTraceLabel(normalized)) {
            return true;
        }
        return false;
    }

    private boolean isPotentialToolTracePrefix(String text) {
        String normalized = normalizeToolTraceText(text);
        if (normalized.isEmpty()) {
            return true;
        }
        if (normalized.length() > 128) {
            return false;
        }
        if (prefixMatches("built-in tool", normalized) || prefixMatches("executing on server", normalized)) {
            return true;
        }
        if (containsOnlyDecorationPrefix(text)) {
            return true;
        }
        if (textBlockAfterToolStructure) {
            return prefixMatches("input:", normalized) || prefixMatches("output:", normalized) || prefixMatches("input", normalized) || prefixMatches("output", normalized);
        }
        return false;
    }

    private boolean startsWithToolTraceLabel(String normalized) {
        return normalized.startsWith("input:") || normalized.startsWith("output:") || normalized.startsWith("input\n") || normalized.startsWith("output\n");
    }

    private boolean prefixMatches(String full, String candidate) {
        return full.startsWith(candidate) || candidate.startsWith(full);
    }

    private boolean containsOnlyDecorationPrefix(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.stripLeading();
        if (stripped.isEmpty()) {
            return true;
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.isLetterOrDigit(stripped.charAt(i))) {
                return false;
            }
        }
        return stripped.length() <= 8;
    }

    private String normalizeToolTraceText(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).stripLeading();
        while (!normalized.isEmpty()) {
            char ch = normalized.charAt(0);
            if (!Character.isLetterOrDigit(ch)) {
                normalized = normalized.substring(1).stripLeading();
                continue;
            }
            break;
        }
        return normalized;
    }

    private boolean containsImageToolResult(JsonObject toolResult) {
        if (toolResult == null || !toolResult.has("content")) {
            return false;
        }
        return containsImageBlock(toolResult.get("content"));
    }

    private boolean containsImageBlock(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String type = getString(obj, "type");
            if (CommonConstants.BLOCK_TYPE_IMAGE.equals(type) || (type != null && type.startsWith("image/"))) {
                return true;
            }
            JsonObject source = obj.has("source") && obj.get("source").isJsonObject()
                    ? obj.getAsJsonObject("source")
                    : null;
            if (source != null && CommonConstants.IMAGE_SOURCE_BASE64.equals(getString(source, "type"))) {
                return true;
            }
            for (String key : obj.keySet()) {
                if (containsImageBlock(obj.get(key))) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsImageBlock(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLikelyUnsupportedImageResult(JsonObject resultObject, String rawError) {
        if (!imageReadToolUseSeen) {
            return false;
        }
        Integer status = getInt(resultObject, "api_error_status");
        if (status != null && status >= 400 && status < 500) {
            return true;
        }
        String lower = rawError == null ? "" : rawError.toLowerCase(Locale.ROOT);
        return lower.contains("image")
                || lower.contains("vision")
                || lower.contains("multimodal")
                || lower.contains("content")
                || lower.contains("model_not_found")
                || lower.contains("model not found")
                || lower.contains("model")
                || lower.contains("unsupported")
                || lower.contains("invalid");
    }

    private boolean shouldReportSilentImageFailure(String resultText, String assistantText) {
        if (!imageReadToolUseSeen || imageUnderstandingObserved) {
            return false;
        }
        if (containsMeaningfulImageUnderstandingText(assistantText)) {
            return false;
        }
        return !containsMeaningfulImageUnderstandingText(resultText);
    }

    private boolean containsMeaningfulImageUnderstandingText(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return false;
        }
        return !isMetaImagePlanningText(normalized);
    }

    private boolean isMetaImagePlanningText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("the user wants me to") && lower.contains("image")) {
            return true;
        }
        if (lower.contains("let me use the read tool") || lower.contains("use the read tool to inspect")) {
            return true;
        }
        if ((lower.contains("read an image file") || lower.contains("read the image") || lower.contains("inspect the image"))
                && (lower.contains("let me") || lower.contains("i need to") || lower.contains("i should"))) {
            return true;
        }
        return (text.contains("Read 工具") || text.contains("read 工具"))
                && (text.contains("查看图片") || text.contains("读取图片") || text.contains("识别图片"));
    }

    private String getToolUseFilePath(JsonObject block) {
        if (block == null || !block.has("input") || !block.get("input").isJsonObject()) {
            return null;
        }
        return getString(block.getAsJsonObject("input"), "file_path");
    }

    private boolean isImagePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        String lower = filePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".webp")
                || lower.endsWith(".svg");
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum TextBlockRoute {
        NONE, UNKNOWN, CONTENT, TOOL_TRACE
    }

    private void emitStreamStartIfNeeded(MessageCallback callback) {
        if (!streamStarted) {
            streamStarted = true;
            callback.onMessage(CliConstants.MSG_STREAM_START,"");
        }
    }

    private void emitMessageStartIfNeeded(MessageCallback callback) {
        if (!messageStarted) {
            messageStarted = true;
            callback.onMessage(CliConstants.MSG_MESSAGE_START,"");
        }
    }
}
