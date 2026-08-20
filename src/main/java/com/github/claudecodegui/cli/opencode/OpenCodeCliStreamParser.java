package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.GatewayDownMatcher;
import com.github.claudecodegui.cli.common.McpErrorMatcher;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * §15.4 / §7.3:OpenCode {@code opencode run --format json} 事件流解析器。
 * <p>
 * 按真实事件 schema 解析(样本实捕自 opencode v1.17.11),输出统一 MSG_* 协议,
 * 经 {@link com.github.claudecodegui.session.CodexMessageHandler} 消费。
 * 每次发送(含 B13 失效重试)构造新实例,持有本次运行的全部可变状态。
 * <p>
 * 事件映射(详见设计 §7.3):
 * <ul>
 *   <li>{@code step_start}(首轮) → {@link CliConstants#MSG_STREAM_START};并从顶层 {@code sessionID}
 *       提取并下发 {@link CliConstants#MSG_SESSION_ID}(仅首轮一次)</li>
 *   <li>{@code text} → {@link CliConstants#MSG_CONTENT_DELTA}({@code part.text})</li>
 *   <li>{@code tool_use} → {@link CommonConstants#MSG_TYPE_TOOL_USE}(tool_use 原始块)+
 *       {@link CommonConstants#MSG_TYPE_TOOL_RESULT}(tool_result 原始块)</li>
 *   <li>{@code step_finish} → {@link CliConstants#MSG_RESULT}(归一 usage,因 handler 无 MSG_USAGE case,
 *       usage 必须经 MSG_RESULT 走 handleResultMessage);{@code part.reason=="stop"} 追加
 *       {@link CliConstants#MSG_STREAM_END}+{@link CliConstants#MSG_MESSAGE_END},
 *       {@code "tool-calls"} 不结束流(后续还有 step)</li>
 *   <li>{@code error} → 收集到 {@link #errorDiagnostic()},由会话层在结束时上报</li>
 * </ul>
 */
public class OpenCodeCliStreamParser {

    private static final Logger LOG = Logger.getInstance(OpenCodeCliStreamParser.class);
    private static final String EVENT_STEP_START = "step_start";
    private static final String EVENT_TEXT = "text";
    private static final String EVENT_TOOL_USE = "tool_use";
    private static final String EVENT_STEP_FINISH = "step_finish";
    private static final String EVENT_ERROR = "error";
    private static final String EVENT_REASONING = "reasoning";
    private static final String REASON_STOP = "stop";

    private final Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean streamStarted;
    private boolean streamEnded;
    private boolean sessionIdEmitted;
    private boolean hasError;
    // MCP 连接失败(本地 server 未启动)已发非阻塞提示的标志:每轮仅发一次 toast(MCP 错误可能多次出现)。
    private boolean mcpNoticeEmitted;
    // 是否解析到至少一个有效 JSON 事件(opencode 产出了事件流)。会话层据此区分
    // "有内容缺收尾"(补发 stream_end)与"零事件静默失败"(上报错误,避免无输出无错误)。
    private boolean receivedAnyEvent;
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final StringBuilder assistantContent = new StringBuilder();
    // 累积 reasoning 文本,增量去重(对称 ai-bridge/services/opencode/event-mapper.js delta)。
    // reasoning 事件由 --thinking flag 触发(opencode run 默认不输出推理文本,见 buildRunCommand)。
    private final StringBuilder reasoningText = new StringBuilder();
    private boolean thinkingActivated; // 首个 reasoning 合成思考激活态(一次性,对称 CLI thinkingStart)

    public OpenCodeCliStreamParser(CliSessionCallback callback) {
        this.callback = callback;
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    /** 本次运行捕获到的 session id(从事件流顶层 sessionID 提取),供会话层缓存与续接。 */
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

    /** 本次运行是否解析到至少一个有效 JSON 事件(opencode 产出了事件流)。 */
    public boolean receivedAnyEvent() {
        return receivedAnyEvent;
    }

    /** 本次运行是否已收到 step_finish(reason=stop)(会话层据此判断是否需补发 stream_end)。 */
    public boolean streamEnded() {
        return streamEnded;
    }

    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    /** B13 失效重试时调用:重试视为新一轮,首轮 step_start 重新触发 stream_start/session_id,清空累积。 */
    void resetForRetry() {
        streamStarted = false;
        streamEnded = false;
        sessionIdEmitted = false;
        hasError = false;
        mcpNoticeEmitted = false;
        receivedAnyEvent = false;
        errorDiagnostic.setLength(0);
        assistantContent.setLength(0);
        reasoningText.setLength(0);
        thinkingActivated = false;
    }

    /**
     * 检测文本是否为 MCP 连接失败(本地 server 未启动等),命中则发非阻塞 status 提示(每轮去重一次),
     * 供会话层非 JSON 噪声分支与本类 {@link #handleError} 复用。镜像 Codex 的降级处理。
     *
     * @return true 表示命中 MCP 连接失败(调用方应跳过 hasError/错误缓冲)
     */
    public boolean emitMcpNoticeIfMatched(String text) {
        // GatewayDownMatcher 先判:[melon-gateway-down] 是 stdio-client 降级标记(更明确),
        // 命中发 GATEWAY_DOWN_NOTICE。OpenCode(Go)最可能透传 MCP server stderr 到此分支。
        if (GatewayDownMatcher.isGatewayDown(text)) {
            if (!mcpNoticeEmitted) {
                mcpNoticeEmitted = true;
                emitter.status(GatewayDownMatcher.GATEWAY_DOWN_NOTICE);
            }
            return true;
        }
        if (!McpErrorMatcher.isMcpConnectionFailure(text)) {
            return false;
        }
        if (!mcpNoticeEmitted) {
            mcpNoticeEmitted = true;
            emitter.status(McpErrorMatcher.MCP_SKIPPED_NOTICE);
        }
        return true;
    }

    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return;
        }
        JsonObject event;
        try {
            event = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[OpenCodeParser] non-JSON line ignored: " + trimmed);
            return;
        }
        if (event == null) {
            return;
        }
        String type = getString(event, "type");
        if (type == null) {
            return;
        }
        // 已确认是一个带 type 的有效事件:标记 opencode 产出了事件流(会话层据此区分静默空失败)。
        receivedAnyEvent = true;
        // sessionID 顶层存在则尽早捕获(每个事件都带),供首轮 step_start 下发
        captureSessionId(event);

        switch (type) {
            case EVENT_STEP_START -> handleStepStart();
            case EVENT_TEXT -> handleText(event);
            case EVENT_REASONING -> handleReasoning(event);
            case EVENT_TOOL_USE -> handleToolUse(event);
            case EVENT_STEP_FINISH -> handleStepFinish(event);
            case EVENT_ERROR -> handleError(event);
            default -> {
                // 忽略未知事件类型(message_start 等内部事件)。
                // 注:reasoning 文本事件由 EVENT_REASONING 分支处理(需命令行带 --thinking 才输出,
                // 见 OpenCodeCliSession.buildRunCommand)。
                // 早期根因调查(2026-07,基于 v1.17.11 --format json cheatsheet 的二手资料)曾误判
                // "CLI 无推理文本事件、--thinking 不改 json schema";2026-07 v1.17.13 实测推翻:
                // `opencode run --format json --thinking` 会产出 type:"reasoning" 文本事件
                // (对称 SDK 的 message.part.updated + part.type=reasoning)。详见 buildRunCommand 注释。
            }
        }
    }

    private void captureSessionId(JsonObject event) {
        if (capturedSessionId != null) {
            return;
        }
        String id = getString(event, "sessionID");
        if (id == null && event.has("part") && event.get("part").isJsonObject()) {
            id = getString(event.getAsJsonObject("part"), "sessionID");
        }
        if (id != null && !id.isBlank()) {
            capturedSessionId = id;
        }
    }

    private void handleStepStart() {
        if (!streamStarted) {
            streamStarted = true;
            emitter.streamStart();
        }
        if (!sessionIdEmitted && capturedSessionId != null) {
            sessionIdEmitted = true;
            emitter.sessionId(capturedSessionId);
        }
    }

    private void handleText(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        String text = getString(part, "text");
        emitter.contentDelta(assistantContent, text);
    }

    /**
     * 处理 reasoning 事件(需 {@code opencode run} 带 {@code --thinking} flag)。opencode 把推理文本
     * 以累积式输出(同 part.id,text 逐次增长),此处增量去重后下发,对称 SDK 路径
     * {@code ai-bridge/services/opencode/event-mapper.js} 的 reasoning→thinking_delta 映射。
     * <p>
     * 首个 reasoning 事件(即使 text 空)发 {@link CommonConstants#MSG_TYPE_THINKING} 激活思考态
     * (对称 CLI thinkingStart → CodexMessageHandler 点亮"思考中"指示灯);后续仅发增量 delta。
     */
    private void handleReasoning(JsonObject event) {
        JsonObject part = asObject(event, "part");
        String text = part != null ? getString(part, "text") : null;
        String delta = deltaOf(reasoningText.toString(), text);
        if (!thinkingActivated) {
            thinkingActivated = true;
            emitter.thinkingStart();
        }
        if (delta != null) {
            String accepted = CliOutputLimits.appendBounded(
                    reasoningText, delta, CliOutputLimits.MAX_REASONING_CHARS);
            if (!accepted.isEmpty()) {
                emitter.thinkingDelta(accepted);
            }
        }
    }

    /**
     * reasoning 增量去重:累积式 text 取前缀差为增量;新文本不以旧为前缀(非累积/重置)则整体下发。
     * 返回 null 表示无新增(空或与旧值相同,不发 delta)。对称 event-mapper.js delta()。
     */
    private static String deltaOf(String previous, String next) {
        String oldText = previous == null ? "" : previous;
        String newText = next == null ? "" : next;
        if (newText.isEmpty() || newText.equals(oldText)) {
            return null;
        }
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    private void handleToolUse(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        String tool = firstNonBlank(getString(part, "tool"), getString(part, "name"), "unknown");
        String callId = firstNonBlank(getString(part, "callID"), getString(part, "id"), "call_" + System.nanoTime());
        JsonObject state = asObject(part, "state");
        JsonObject input = asObject(state, "input");
        String output = getString(state, "output");
        boolean isError = isErrorState(state);

        // tool_use 原始块(Anthropic schema,CodexMessageHandler.handleToolUse 经 wrapAsAssistantRaw 包装)
        JsonObject toolUseBlock = new JsonObject();
        toolUseBlock.addProperty("type", "tool_use");
        toolUseBlock.addProperty("id", callId);
        toolUseBlock.addProperty("name", tool);
        toolUseBlock.add("input", input);
        emitter.toolUse(toolUseBlock);

        // tool_result 原始块(CodexMessageHandler.handleToolResult 经 wrapAsUserRaw 包装)
        JsonObject toolResultBlock = new JsonObject();
        toolResultBlock.addProperty("type", "tool_result");
        toolResultBlock.addProperty("tool_use_id", callId);
        toolResultBlock.addProperty("is_error", isError);
        toolResultBlock.addProperty("content", output != null ? output : "(running)");
        emitter.toolResult(toolResultBlock);
    }

    private void handleStepFinish(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        // usage 经 MSG_RESULT 下发(CodexMessageHandler 无 MSG_USAGE case,usage 必须经 MSG_RESULT)
        JsonObject tokens = asObject(part, "tokens");
        if (tokens != null) {
            JsonObject usage = buildUsage(tokens);
            JsonObject resultWrapper = new JsonObject();
            resultWrapper.add("usage", usage);
            emitter.result(resultWrapper.toString());
        }
        if (REASON_STOP.equals(getString(part, "reason"))) {
            streamEnded = true;
            emitter.streamEnd();
            emitter.messageEnd();
        }
    }

    private void handleError(JsonObject event) {
        String message = null;
        JsonObject err = asObject(event, "error");
        if (err != null) {
            JsonObject data = asObject(err, "data");
            if (data != null) {
                message = getString(data, "message");
            }
            if (message == null) {
                message = getString(err, "message");
            }
        }
        if (message == null) {
            message = getString(event, "message");
        }
        if (message == null) {
            message = event.toString();
        }
        // MCP 连接失败(本地 server 未启动):降级为非阻塞提示,不标记 hasError/缓冲为回合错误。
        // 镜像 Codex CLI 诊断分支与 Codex SDK [SEND_ERROR] 的降级处理(Principle 6 对称)。
        if (emitMcpNoticeIfMatched(message)) {
            return;
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

    private JsonObject buildUsage(JsonObject tokens) {
        JsonObject cache = asObject(tokens, "cache");
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", getInt(tokens, "input"));
        usage.addProperty("output_tokens", getInt(tokens, "output"));
        usage.addProperty("cache_read_input_tokens", getInt(cache, "read"));
        usage.addProperty("cache_creation_input_tokens", getInt(cache, "write"));
        return usage;
    }

    private static boolean isErrorState(JsonObject state) {
        if (state == null) {
            return false;
        }
        String status = getString(state, "status");
        if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            return true;
        }
        if (state.has("error") && !state.get("error").isJsonNull()) {
            return true;
        }
        JsonObject metadata = asObject(state, "metadata");
        if (metadata != null && metadata.has("exit") && metadata.get("exit").isJsonPrimitive()) {
            try {
                return metadata.get("exit").getAsInt() != 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static JsonObject asObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
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

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
