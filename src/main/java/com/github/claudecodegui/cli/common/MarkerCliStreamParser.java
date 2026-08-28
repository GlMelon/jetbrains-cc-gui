package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 共享 marker 协议 CLI 流解析器(omp/dsh 两条 channel 复用)。
 * <p>
 * 解析 stdout 逐行文本,检测 {@code [TAG]} 标记,输出统一 MSG_* 协议,
 * 经 {@link CliSectionEmitter} 消费。协议定义见 ai-bridge/utils/marker-protocol.js。
 * <p>
 * marker 标记:
 * <ul>
 *   <li>{@code [STREAM_START]} → {@link CliConstants#MSG_STREAM_START}</li>
 *   <li>{@code [MESSAGE_START]} → {@link CliConstants#MSG_MESSAGE_START}</li>
 *   <li>{@code [CONTENT_DELTA] <json>} → 提取 text 字段 → {@link CliConstants#MSG_CONTENT_DELTA}</li>
 *   <li>{@code [THINKING_DELTA] <json>} → 提取 text 字段,首条补 thinkingStart → 思考区增量
 *       (增量式,无需前缀差去重;对齐 GrokCliStreamParser.handleThought)</li>
 *   <li>{@code [SESSION_ID] <id>} → {@link CliConstants#MSG_SESSION_ID}(仅首次下发)</li>
 *   <li>{@code [USAGE] <json>} → {@link CliConstants#MSG_USAGE}</li>
 *   <li>{@code [STREAM_END]} → {@link CliConstants#MSG_STREAM_END} + {@link CliConstants#MSG_MESSAGE_END}</li>
 *   <li>{@code [MESSAGE_END]} → {@link CliConstants#MSG_MESSAGE_END}</li>
 *   <li>{@code [MESSAGE] <json>} → 解析 type 字段分流:tool_use → {@link CommonConstants#MSG_TYPE_TOOL_USE},
 *       tool_result → {@link CommonConstants#MSG_TYPE_TOOL_RESULT}</li>
 *   <li>{@code [SEND_ERROR] <json>} → 收集到 {@link #errorDiagnostic()}</li>
 * </ul>
 * 每次发送构造新实例,持有本次运行的全部可变状态。
 */
public class MarkerCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(MarkerCliStreamParser.class);

    private static final String TAG_STREAM_START = "[STREAM_START]";
    private static final String TAG_MESSAGE_START = "[MESSAGE_START]";
    private static final String TAG_CONTENT_DELTA = "[CONTENT_DELTA]";
    private static final String TAG_THINKING_DELTA = "[THINKING_DELTA]";
    private static final String TAG_SESSION_ID = "[SESSION_ID]";
    private static final String TAG_USAGE = "[USAGE]";
    private static final String TAG_STREAM_END = "[STREAM_END]";
    private static final String TAG_MESSAGE_END = "[MESSAGE_END]";
    private static final String TAG_MESSAGE = "[MESSAGE]";
    private static final String TAG_SEND_ERROR = "[SEND_ERROR]";

    private final Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean streamStarted;
    private boolean streamEnded;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private boolean receivedAnyEvent;
    private boolean thinkingActivated;
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final StringBuilder assistantContent = new StringBuilder();

    public MarkerCliStreamParser(CliSessionCallback callback) {
        this.callback = callback;
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    /** 本次运行捕获到的 session id(从 [SESSION_ID] 标记提取),供会话层缓存与续接。 */
    public String capturedSessionId() {
        return capturedSessionId;
    }

    /** 累积的 assistant 文本(供会话层 onComplete 的 finalResult)。 */
    public String accumulatedText() {
        return assistantContent.toString();
    }

    public boolean hasError() {
        return hasError;
    }

    /** 本次运行是否解析到至少一个有效 marker 事件。 */
    public boolean receivedAnyEvent() {
        return receivedAnyEvent;
    }

    /** 本次运行是否已收到 [STREAM_END](会话层据此判断是否需补发 stream_end)。 */
    public boolean streamEnded() {
        return streamEnded;
    }

    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    /**
     * 逐行解析 marker 协议。每行应以 [TAG] 开头,可能携带空格分隔的 JSON 参数。
     * 非 marker 行静默忽略。
     */
    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (trimmed.startsWith(TAG_STREAM_START)) {
            handleStreamStart();
        } else if (trimmed.startsWith(TAG_MESSAGE_START)) {
            handleMessageStart();
        } else if (trimmed.startsWith(TAG_CONTENT_DELTA)) {
            handleContentDelta(trimmed);
        } else if (trimmed.startsWith(TAG_THINKING_DELTA)) {
            handleThinkingDelta(trimmed);
        } else if (trimmed.startsWith(TAG_SESSION_ID)) {
            handleSessionId(trimmed);
        } else if (trimmed.startsWith(TAG_USAGE)) {
            handleUsage(trimmed);
        } else if (trimmed.startsWith(TAG_STREAM_END)) {
            handleStreamEnd();
        } else if (trimmed.startsWith(TAG_MESSAGE_END)) {
            handleMessageEnd();
        } else if (trimmed.startsWith(TAG_MESSAGE)) {
            handleMessage(trimmed);
        } else if (trimmed.startsWith(TAG_SEND_ERROR)) {
            handleSendError(trimmed);
        }
        // 非 marker 行静默忽略
    }

    private void handleStreamStart() {
        receivedAnyEvent = true;
        if (!streamStarted) {
            streamStarted = true;
            emitter.streamStart();
        }
    }

    private void handleMessageStart() {
        receivedAnyEvent = true;
        emitter.messageStart();
    }

    private void handleContentDelta(String line) {
        receivedAnyEvent = true;
        String payload = extractPayload(line, TAG_CONTENT_DELTA);
        if (payload == null) {
            return;
        }
        String text = extractJsonStringField(payload, "text");
        if (text != null) {
            emitter.contentDelta(assistantContent, text);
        } else {
            // 无 text 字段:尝试把 payload 本身作为纯文本下发(向后兼容)
            emitter.contentDelta(assistantContent, payload);
        }
    }

    /**
     * 处理 [THINKING_DELTA] 标记:omp thinking_delta / dsh reasoning-delta 的增量文本。
     * 事件为<b>增量式</b>(每条为新增片段),首条前补发 thinkingStart(思考区卡片创建),
     * 后续直接 thinkingDelta——与 GrokCliStreamParser.handleThought 同构。
     */
    private void handleThinkingDelta(String line) {
        receivedAnyEvent = true;
        String payload = extractPayload(line, TAG_THINKING_DELTA);
        if (payload == null) {
            return;
        }
        String text = extractJsonStringField(payload, "text");
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!thinkingActivated) {
            thinkingActivated = true;
            emitter.thinkingStart();
        }
        emitter.thinkingDelta(text);
    }

    private void handleSessionId(String line) {
        receivedAnyEvent = true;
        String payload = extractPayload(line, TAG_SESSION_ID);
        if (payload == null || payload.isBlank()) {
            return;
        }
        if (capturedSessionId == null) {
            capturedSessionId = payload.trim();
        }
        if (!sessionIdEmitted) {
            sessionIdEmitted = true;
            emitter.sessionId(capturedSessionId);
        }
    }

    private void handleUsage(String line) {
        receivedAnyEvent = true;
        String payload = extractPayload(line, TAG_USAGE);
        if (payload != null) {
            emitter.usage(payload);
        }
    }

    private void handleStreamEnd() {
        receivedAnyEvent = true;
        if (!streamEnded) {
            streamEnded = true;
            emitter.streamEnd();
        }
    }

    private void handleMessageEnd() {
        receivedAnyEvent = true;
        emitter.messageEnd();
    }

    /**
     * 处理 [MESSAGE] 标记:解析 JSON 中的 type 字段,分流 tool_use / tool_result。
     * 若无 type 字段或未知类型,记录诊断日志。
     */
    private void handleMessage(String line) {
        receivedAnyEvent = true;
        String payload = extractPayload(line, TAG_MESSAGE);
        if (payload == null || payload.isBlank()) {
            return;
        }
        JsonObject obj;
        try {
            obj = gson.fromJson(payload.trim(), JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[MarkerParser] [MESSAGE] payload not valid JSON: " + payload);
            return;
        }
        if (obj == null) {
            return;
        }
        String type = getString(obj, "type");
        if (type == null) {
            LOG.debug("[MarkerParser] [MESSAGE] missing type field: " + payload);
            return;
        }
        switch (type) {
            case CommonConstants.MSG_TYPE_TOOL_USE -> emitter.toolUse(obj);
            case CommonConstants.MSG_TYPE_TOOL_RESULT -> emitter.toolResult(obj);
            default -> {
                // 未知 MESSAGE type:按原始 JSON 下发(向后兼容新类型)
                emitter.assistantRaw(obj);
            }
        }
    }

    private void handleSendError(String line) {
        String payload = extractPayload(line, TAG_SEND_ERROR);
        String message;
        if (payload != null && !payload.isBlank()) {
            // 尝试从 JSON 中提取 message 字段
            message = extractJsonStringField(payload.trim(), "message");
            if (message == null) {
                message = payload.trim();
            }
        } else {
            message = "[SEND_ERROR] (no payload)";
        }
        hasError = true;
        if (errorDiagnostic.length() >= CliOutputLimits.MAX_DIAGNOSTIC_CHARS) {
            return;
        }
        if (errorDiagnostic.length() > 0) {
            errorDiagnostic.append('\n');
        }
        CliOutputLimits.appendBounded(
                errorDiagnostic, message, CliOutputLimits.MAX_DIAGNOSTIC_CHARS);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 从 marker 行中提取标签后的 payload 部分。
     * 例如 {@code "[CONTENT_DELTA] {\"text\":\"hi\"}"} → {@code "{\"text\":\"hi\"}"}。
     *
     * @param line      完整行(已 trim)
     * @param tagPrefix 标签前缀,如 {@code "[CONTENT_DELTA]"}
     * @return payload 字符串(已 trim),若标签后无内容则返回 null
     */
    private static String extractPayload(String line, String tagPrefix) {
        if (line.length() <= tagPrefix.length()) {
            return null;
        }
        String rest = line.substring(tagPrefix.length());
        if (rest.isBlank()) {
            return null;
        }
        return rest.trim();
    }

    /**
     * 从 JSON 字符串中提取指定字符串字段值。
     * 简易解析:反序列化为 JsonObject 后读取,避免手写字符串索引。
     */
    private String extractJsonStringField(String jsonString, String fieldName) {
        try {
            JsonObject obj = gson.fromJson(jsonString, JsonObject.class);
            if (obj != null) {
                return getString(obj, fieldName);
            }
        } catch (Exception ignored) {
            // 非 JSON 格式:返回 null
        }
        return null;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }
}
