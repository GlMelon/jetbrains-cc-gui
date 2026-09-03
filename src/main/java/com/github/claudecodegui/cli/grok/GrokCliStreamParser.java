package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.cli.common.McpErrorMatcher;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Grok CLI streaming-json 事件流解析器(对齐 ai-bridge/services/grok/message-service.js 映射)。
 * <p>
 * 双格式入口,行级路由:
 * <ul>
 *   <li><b>marker 行</b>({@code [TAG]} 开头):工具信号经 {@link GrokToolHistoryTailer} 以
 *       {@code [MESSAGE] {...}} 合成注入(chat_history 尾随),另支持
 *       {@code [SESSION_ID]}/{@code [USAGE]};</li>
 *   <li><b>JSON 事件行</b>(streaming-json NDJSON):
 *       {@code {"type":"text","data":"..."}} → MSG_CONTENT_DELTA;
 *       {@code {"type":"thought","data":"..."}} → 思考激活 + MSG_THINKING_DELTA(grok 为增量式);
 *       {@code {"type":"end","sessionId":..,"usage":{..}}} → SESSION_ID/USAGE 捕获,
 *       但流结束延迟到 {@link #finishStream()}(须等最终 chat_history drain 完成);</li>
 * </ul>
 * 每次发送构造新实例(非线程安全;tailer 注入经会话层 synchronize 串行化)。
 */
public class GrokCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(GrokCliStreamParser.class);

    /** marker 标签前缀 */
    private static final String TAG_MESSAGE = "[MESSAGE]";
    private static final String TAG_SESSION_ID = "[SESSION_ID]";
    private static final String TAG_USAGE = "[USAGE]";
    private static final String TAG_STREAM_END = "[STREAM_END]";
    /** grok streaming-json 事件 type 值 */
    private static final String EVENT_TEXT = "text";
    private static final String EVENT_THOUGHT = "thought";
    private static final String EVENT_END = "end";
    private static final String EVENT_ERROR = "error";

    private final com.google.gson.Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean sessionIdEmitted;
    private boolean streamStarted;
    private boolean streamEnded;
    /** 收到 end 事件但尚未 finishStream():最终 tailer drain 应先于流结束。 */
    private boolean endSeen;
    private boolean thinkingActivated;
    private boolean hasError;
    private boolean mcpNoticeEmitted;
    private boolean receivedAnyEvent;
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final StringBuilder assistantContent = new StringBuilder();

    public GrokCliStreamParser(CliSessionCallback callback) {
        this.callback = callback;
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    @Override
    public String capturedSessionId() {
        return capturedSessionId;
    }

    @Override
    public String accumulatedText() {
        return assistantContent.toString();
    }

    @Override
    public boolean hasError() {
        return hasError;
    }

    @Override
    public boolean receivedAnyEvent() {
        return receivedAnyEvent;
    }

    @Override
    public boolean streamEnded() {
        return streamEnded;
    }

    @Override
    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    /**
     * 首个内容/思考/工具事件前发 stream_start(对称 bridge beginStream 的前端依赖:
     * 先建立流再推增量)。惰性触发,避免 grok 启动横幅期间早开空流。
     */
    private void ensureStreamStarted() {
        if (!streamStarted) {
            streamStarted = true;
            emitter.streamStart();
            emitter.messageStart();
        }
    }

    @Override
    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("[")) {
            handleMarkerLine(trimmed);
            return;
        }
        JsonObject event;
        try {
            event = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[GrokParser] non-JSON line ignored: " + preview(trimmed));
            return;
        }
        if (event == null || !event.has("type") || !event.get("type").isJsonPrimitive()) {
            return;
        }
        receivedAnyEvent = true;
        switch (event.get("type").getAsString()) {
            case EVENT_TEXT -> handleText(event);
            case EVENT_THOUGHT -> handleThought(event);
            case EVENT_END -> handleEnd(event);
            case EVENT_ERROR -> handleError(event);
            default -> {
                // 其余 ACP/streaming 事件忽略(与旧 bridge 行为一致)
            }
        }
    }

    // ── marker 分支(tailer 合成工具信号 + 会话 id/usage 兜底) ──────────────────

    private void handleMarkerLine(String line) {
        if (line.startsWith(TAG_STREAM_END)) {
            if (!streamEnded) {
                streamEnded = true;
                emitter.streamEnd();
                emitter.messageEnd();
            }
            return;
        }
        if (line.startsWith(TAG_SESSION_ID)) {
            receivedAnyEvent = true;
            String payload = payloadAfter(line, TAG_SESSION_ID);
            if (payload != null && !payload.isBlank() && capturedSessionId == null) {
                capturedSessionId = payload.trim();
                emitSessionIdOnce();
            }
            return;
        }
        if (line.startsWith(TAG_USAGE)) {
            receivedAnyEvent = true;
            String payload = payloadAfter(line, TAG_USAGE);
            if (payload != null && !payload.isBlank()) {
                ensureStreamStarted();
                emitter.usage(payload);
            }
            return;
        }
        if (line.startsWith(TAG_MESSAGE)) {
            receivedAnyEvent = true;
            String payload = payloadAfter(line, TAG_MESSAGE);
            if (payload == null || payload.isBlank()) {
                return;
            }
            JsonObject obj;
            try {
                obj = gson.fromJson(payload.trim(), JsonObject.class);
            } catch (Exception e) {
                LOG.debug("[GrokParser] [MESSAGE] payload not valid JSON: " + preview(payload));
                return;
            }
            if (obj == null) {
                return;
            }
            String type = obj.has("type") && obj.get("type").isJsonPrimitive()
                    ? obj.get("type").getAsString() : null;
            ensureStreamStarted();
            // 两代形态都兼容:Claude 信封式(顶层 assistant/user,content 块内才是
            // tool_use/tool_result——chat_history 尾随产物的形态)与扁平式
            // (顶层即 tool_use/tool_result)。均归一为独立 wire 块下发。
            switch (type == null ? "" : type) {
                case CommonConstants.MSG_TYPE_TOOL_USE -> emitter.toolUse(firstContentBlock(obj, "tool_use") != null
                        ? firstContentBlock(obj, "tool_use") : obj);
                case CommonConstants.MSG_TYPE_TOOL_RESULT -> emitter.toolResult(
                        firstContentBlock(obj, "tool_result") != null
                                ? firstContentBlock(obj, "tool_result") : obj);
                case "assistant" -> {
                    JsonObject block = firstContentBlock(obj, "tool_use");
                    if (block != null) {
                        emitter.toolUse(block);
                    } else {
                        emitter.assistantRaw(obj);
                    }
                }
                case "user" -> {
                    JsonObject block = firstContentBlock(obj, "tool_result");
                    emitter.toolResult(block != null ? block : obj);
                }
                default -> emitter.assistantRaw(obj);
            }
        }
        // 其余 [TAG](STREAM_START 等)由本解析器自行管理生命周期,忽略外部重复。
    }

    private void emitSessionIdOnce() {
        if (!sessionIdEmitted && capturedSessionId != null) {
            sessionIdEmitted = true;
            emitter.sessionId(capturedSessionId);
        }
    }

    /** 从 Claude 信封 [MESSAGE]{message:{content:[...]}} 提取指定类型的首个 content 块;形态不符返回 null。 */
    private static JsonObject firstContentBlock(JsonObject envelope, String blockType) {
        try {
            if (envelope.has("message") && envelope.get("message").isJsonObject()
                    && envelope.getAsJsonObject("message").has("content")
                    && envelope.getAsJsonObject("message").get("content").isJsonArray()
                    && !envelope.getAsJsonObject("message").getAsJsonArray("content").isEmpty()) {
                JsonObject first = envelope.getAsJsonObject("message")
                        .getAsJsonArray("content").get(0).getAsJsonObject();
                if (first.has("type") && blockType.equals(first.get("type").getAsString())) {
                    return first;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    // ── streaming-json 事件分支 ────────────────────────────────────────────────

    private void handleText(JsonObject event) {
        String data = stringData(event);
        if (data == null || data.isEmpty()) {
            return;
        }
        ensureStreamStarted();
        emitter.contentDelta(assistantContent, data);
    }

    /**
     * thought 事件为<b>增量式</b>(对称旧 bridge:每条直接 THINKING_DELTA),
     * 与 opencode 的累积式不同——无需前缀差去重。
     */
    private void handleThought(JsonObject event) {
        String data = stringData(event);
        if (!thinkingActivated) {
            thinkingActivated = true;
            ensureStreamStarted();
            emitter.thinkingStart();
        }
        if (data != null && !data.isEmpty()) {
            emitter.thinkingDelta(data);
        }
    }

    /**
     * end 事件:捕获 sessionId/usage,但<b>不立即结束流</b>——chat_history 尾部工具结果
     * 可能晚于 stdout end 到达,须等 {@link #finishStream()}(onStopAuxiliary 最终 drain 后调用)。
     */
    private void handleEnd(JsonObject event) {
        endSeen = true;
        String sessionId = str(event, "sessionId");
        if (sessionId != null && !sessionId.isBlank() && capturedSessionId == null) {
            capturedSessionId = sessionId.trim();
            emitSessionIdOnce();
        } else {
            emitSessionIdOnce();
        }
        if (event.has("usage") && event.get("usage").isJsonObject()) {
            ensureStreamStarted();
            emitter.usage(GsonHolder.GSON.toJson(event.getAsJsonObject("usage")));
        }
    }

    private void handleError(JsonObject event) {
        String message = str(event, "message");
        if (message == null) {
            message = event.toString();
        }
        if (enrichGrokAuthError(message)) {
            return;
        }
        if (emitMcpNoticeIfMatched(message)) {
            return;
        }
        hasError = true;
        appendDiagnosticBounded(message);
    }

    /**
     * MCP 连接失败降级提示(镜像 OpenCodeCliStreamParser.emitMcpNoticeIfMatched):
     * 命中 McpErrorMatcher 发非阻塞 status,每轮至多一次 toast。
     *
     * @return true 表示命中(调用方跳过 hasError/错误缓冲)
     */
    @Override
    public boolean emitMcpNoticeIfMatched(String text) {
        if (!McpErrorMatcher.isMcpConnectionFailure(text)) {
            return false;
        }
        if (!mcpNoticeEmitted) {
            mcpNoticeEmitted = true;
            emitter.status(McpErrorMatcher.MCP_SKIPPED_NOTICE);
        }
        return true;
    }

    /**
     * 结束本轮流(end 事件已见或进程正常退出均适用):幂等。
     * 由会话层 onStopAuxiliary(最终 chat_history drain 之后)调用。
     */
    void finishStream() {
        if (streamEnded) {
            return;
        }
        streamEnded = true;
        if (streamStarted) {
            emitter.streamEnd();
            emitter.messageEnd();
        }
    }

    /** end 事件是否出现过(诊断用:未见 end 即退出 → CLI 异常终止)。 */
    boolean sawEndEvent() {
        return endSeen;
    }

    // ── 私有工具 ───────────────────────────────────────────────────────────────

    /** 官方代理无凭证提示增强(对称 JS enrichGrokAuthError);命中返回 true。 */
    private boolean enrichGrokAuthError(String message) {
        String text = String.valueOf(message);
        if (!text.matches("(?i).*(cli-chat-proxy\\.grok\\.com|auth_kind=none|Unauthorized \\(401\\)).*")) {
            return false;
        }
        hasError = true;
        appendDiagnosticBounded(text + "\n\nHint: Grok CLI used official cli-chat-proxy instead of your "
                + "~/.grok/config.toml profile. Pass the profile name with -m or omit -m.");
        return true;
    }

    private void appendDiagnosticBounded(String message) {
        if (errorDiagnostic.length() >= CliOutputLimits.MAX_DIAGNOSTIC_CHARS) {
            return;
        }
        if (errorDiagnostic.length() > 0) {
            errorDiagnostic.append('\n');
        }
        CliOutputLimits.appendBounded(errorDiagnostic, message, CliOutputLimits.MAX_DIAGNOSTIC_CHARS);
    }

    private static String stringData(JsonObject event) {
        JsonElement el = event.get("data");
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()
                || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static String payloadAfter(String line, String tagPrefix) {
        if (line.length() <= tagPrefix.length()) {
            return null;
        }
        String rest = line.substring(tagPrefix.length()).trim();
        return rest.isEmpty() ? null : rest;
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
