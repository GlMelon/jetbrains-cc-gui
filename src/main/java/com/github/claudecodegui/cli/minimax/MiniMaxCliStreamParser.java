package com.github.claudecodegui.cli.minimax;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * MiniMax Code(mcode)stream-json 事件流解析器({@code minimax exec --output-format stream-json})。
 * <p>
 * 事件映射(同源移植自上游 ai-bridge/services/minimax/message-service.js,兼容两代协议):
 * <ul>
 *   <li><b>0.2.x 扁平事件</b>:{@code delta.thinking} → 思考激活 + MSG_THINKING_DELTA;
 *       {@code delta.content} → MSG_CONTENT_DELTA;{@code delta.toolCalls[]}
 *       (status 1=start / 2=done)→ tool_use / tool_result(id 去重,start/done 非严格配平时
 *       done 补发 tool_use);{@code message}(role=assistant 带 usage)→ MSG_USAGE;安全网:
 *       整段未流过 delta 的 message 一次性补发全量 content/thinking(非流式回退);</li>
 *   <li><b>0.4.x schemaVersion 1 信封(item 化)</b>:{@code item.started/updated/completed}
 *       (item.type:reasoning|agent_message|tool_call)→ thinking/content/tool 事件;
 *       item.completed 携带全量 text,按 item.id(无 id 用同类匿名键)去重防重复;
 *       {@code turn.completed}(usage)→ MSG_USAGE;{@code turn.failed}(结构化 error)→
 *       立即记错误(与 exec.completed 的重复失败只发一次);{@code exec.completed} 内嵌
 *       exec.result → 与顶层 {@code exec.result} 同语义(sessionId/status/answer/error);</li>
 *   <li>toolCall.status 以 Number() 归一(字符串 "1"/"2" 与数值等价;4/5 为排队/运行中前置态忽略);</li>
 *   <li>{@code exec.result}(sessionId/status)→ SESSION_ID;status 非 "succeeded" 且整轮
 *       无任何输出 → 记错误诊断(进程随即被 isCompletionMarkerLine 钩子终止,错误不能静默);
 *       整轮无内容而 result 带 answer → 补发一次 answer 兜底;</li>
 *   <li>heartbeat / session-status / generic 等忽略。</li>
 * </ul>
 * 每次发送构造新实例(非线程安全)。
 */
public class MiniMaxCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(MiniMaxCliStreamParser.class);
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_MESSAGE = "message";
    private static final String EVENT_RESULT = "exec.result";
    private static final String EVENT_ITEM_STARTED = "item.started";
    private static final String EVENT_ITEM_UPDATED = "item.updated";
    private static final String EVENT_ITEM_COMPLETED = "item.completed";
    private static final String EVENT_TURN_COMPLETED = "turn.completed";
    private static final String EVENT_TURN_FAILED = "turn.failed";
    private static final String EVENT_EXEC_COMPLETED = "exec.completed";
    private static final String ITEM_TYPE_REASONING = "reasoning";
    private static final String ITEM_TYPE_AGENT_MESSAGE = "agent_message";
    private static final String ITEM_TYPE_TOOL_CALL = "tool_call";
    private static final String STATUS_SUCCEEDED = "succeeded";
    /** 0.4.x 无 id item 的去重匿名键(同类只补发一次全量,对称上游 ANON_*_KEY)。 */
    private static final String ANON_CONTENT_KEY = "<anon-agent_message>";
    private static final String ANON_THINKING_KEY = "<anon-reasoning>";
    private static final int TOOL_STATUS_DONE = 2;
    private static final int TOOL_STATUS_START = 1;

    private final Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private boolean errorEmitted;
    private boolean receivedAnyEvent;
    private boolean thinkingActivated;
    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final Set<String> seenToolUseIds = new HashSet<>();
    private final Set<String> seenToolResultIds = new HashSet<>();
    private final Set<String> streamedContentMessageIds = new HashSet<>();
    private final Set<String> streamedThinkingMessageIds = new HashSet<>();

    public MiniMaxCliStreamParser(CliSessionCallback callback) {
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
        // exec.result 即终态行(其后 CLI 空转由会话层终止),视为流已收尾。
        return capturedSessionId != null || hasError;
    }

    @Override
    public String errorDiagnostic() {
        return errorDiagnostic.toString();
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
        JsonObject event;
        try {
            event = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[MiniMaxParser] non-JSON line ignored: " + preview(trimmed));
            return;
        }
        if (event == null || !event.has("type") || !event.get("type").isJsonPrimitive()) {
            return;
        }
        receivedAnyEvent = true;
        switch (event.get("type").getAsString()) {
            case EVENT_DELTA -> handleDelta(event);
            case EVENT_MESSAGE -> handleMessage(event);
            case EVENT_RESULT -> handleResult(event, null);
            case EVENT_ITEM_STARTED, EVENT_ITEM_UPDATED -> handleItem(event, false);
            case EVENT_ITEM_COMPLETED -> handleItem(event, true);
            case EVENT_TURN_COMPLETED -> handleTurnCompleted(event);
            case EVENT_TURN_FAILED -> handleTurnFailed(event);
            case EVENT_EXEC_COMPLETED -> handleExecCompleted(event);
            default -> {
                // heartbeat / session-status / generic 等 UI 噪声忽略。
            }
        }
    }

    private void handleDelta(JsonObject event) {
        String messageId = primitiveString(event.get("messageId"));
        String thinking = primitiveString(event.get("thinking"));
        if (thinking != null && !thinking.isEmpty()) {
            streamedThinkingMessageIds.add(dedupKey(messageId, ANON_THINKING_KEY));
            if (!thinkingActivated) {
                thinkingActivated = true;
                emitter.thinkingStart();
            }
            emitter.thinkingDelta(thinking);
        }
        String content = primitiveString(event.get("content"));
        if (content != null && !content.isEmpty()) {
            streamedContentMessageIds.add(dedupKey(messageId, ANON_CONTENT_KEY));
            emitter.contentDelta(assistantContent, content);
        }
        JsonElement toolCalls = event.get("toolCalls");
        if (toolCalls == null || !toolCalls.isJsonArray()) {
            return;
        }
        for (JsonElement el : toolCalls.getAsJsonArray()) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject call = el.getAsJsonObject();
            String id = primitiveString(call.get("id"));
            String name = primitiveString(call.get("name"));
            if (name == null || name.isEmpty()) {
                name = "tool";
            }
            String toolCallId = id != null && !id.isEmpty() ? id : "minimax-tool-" + name;
            int status = normalizedToolStatus(call.get("status"));
            if (status == TOOL_STATUS_START) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
            } else if (status == TOOL_STATUS_DONE) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
                emitToolResultOnce(toolCallId, extractToolOutputText(call.get("output")));
            }
        }
    }

    /** 0.4.x item 事件(item.started/updated/completed)。isFinal 时 item.content 为全量兜底。 */
    private void handleItem(JsonObject event, boolean isFinal) {
        JsonObject item = asObject(event, "item");
        if (item == null) {
            return;
        }
        String itemType = primitiveString(item.get("type"));
        String itemId = primitiveString(item.get("id"));
        if (ITEM_TYPE_TOOL_CALL.equals(itemType)) {
            JsonObject call = asObject(item, "toolCall");
            if (call == null) {
                return;
            }
            String id = primitiveString(call.get("id"));
            String name = primitiveString(call.get("name"));
            if (name == null || name.isEmpty()) {
                name = "tool";
            }
            String toolCallId = id != null && !id.isEmpty() ? id : "minimax-tool-" + name;
            int status = normalizedToolStatus(call.get("status"));
            if (status == TOOL_STATUS_DONE) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
                emitToolResultOnce(toolCallId, extractToolOutputText(call.get("output")));
            } else if (status == TOOL_STATUS_START) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
            }
            // 4/5 为排队/运行中前置态,无新载荷,忽略。
            return;
        }
        String data = isFinal
                ? orEmpty(primitiveString(item.get("content")))
                : orEmpty(primitiveString(item.get("contentDelta")));
        if (data.isEmpty()) {
            return;
        }
        if (ITEM_TYPE_REASONING.equals(itemType)) {
            if (isFinal) {
                emitCompletedItemFullText(streamedThinkingMessageIds, dedupKey(itemId, ANON_THINKING_KEY),
                        data, true);
            } else {
                streamedThinkingMessageIds.add(dedupKey(itemId, ANON_THINKING_KEY));
                activateThinking();
                emitter.thinkingDelta(data);
            }
        } else if (ITEM_TYPE_AGENT_MESSAGE.equals(itemType)) {
            if (isFinal) {
                emitCompletedItemFullText(streamedContentMessageIds, dedupKey(itemId, ANON_CONTENT_KEY),
                        data, false);
            } else {
                streamedContentMessageIds.add(dedupKey(itemId, ANON_CONTENT_KEY));
                emitter.contentDelta(assistantContent, data);
            }
        }
        // 其余 item.type(file_change 等)忽略。
    }

    /** item.completed 全量文本兜底:该 key(或匿名键)未流过 delta 才补发,防重复。 */
    private void emitCompletedItemFullText(Set<String> streamedKeys, String key, String data,
                                           boolean thinking) {
        if (!streamedKeys.add(key)) {
            return;
        }
        if (thinking) {
            activateThinking();
            emitter.thinkingDelta(data);
        } else {
            emitter.contentDelta(assistantContent, data);
        }
    }

    private void activateThinking() {
        if (!thinkingActivated) {
            thinkingActivated = true;
            emitter.thinkingStart();
        }
    }

    private void handleTurnCompleted(JsonObject event) {
        JsonObject usage = asObject(event, "usage");
        if (usage != null) {
            emitter.usage(gson.toJson(usage));
        }
    }

    /** 0.4.x turn.failed:结构化错误立即上报(进程在 exec.completed 前不终止,失败不能静默)。 */
    private void handleTurnFailed(JsonObject event) {
        String errorMessage = extractErrorText(event.get("error"), event.get("message"));
        if (errorMessage.isEmpty() || errorEmitted) {
            return;
        }
        emitErrorOnce(errorMessage);
    }

    /** 0.4.x exec.completed:内嵌 exec.result 字段提升后复用顶层结果行语义。 */
    private void handleExecCompleted(JsonObject event) {
        handleResult(event, asObject(event, "result"));
    }

    /**
     * 结果行语义(0.2.x 顶层 {@code exec.result} / 0.4.x {@code exec.completed} 内嵌 result 共用)。
     * nested 非 null 时字段优先取内嵌 result,回退顶层。
     */
    private void handleResult(JsonObject event, JsonObject nested) {
        String sessionId = firstNonBlank(
                nested != null ? primitiveString(nested.get("sessionId")) : null,
                primitiveString(event.get("sessionId")));
        if (sessionId != null && !sessionId.isBlank()) {
            capturedSessionId = sessionId.trim();
            if (!sessionIdEmitted) {
                sessionIdEmitted = true;
                emitter.sessionId(capturedSessionId);
            }
        }
        String status = firstNonBlank(
                nested != null ? primitiveString(nested.get("status")) : null,
                primitiveString(event.get("status")));
        // 非显式 succeeded 一律计失败:结果行后流即终止,失败运行不能静默结束。
        boolean failed = status != null && !STATUS_SUCCEEDED.equalsIgnoreCase(status.trim());
        // 整轮无内容而结果带 answer → 补发一次(非流式最终兜底)。
        String answer = firstNonBlank(
                nested != null ? primitiveString(nested.get("answer")) : null,
                primitiveString(event.get("answer")));
        if (answer != null && !answer.isBlank() && streamedContentMessageIds.isEmpty()) {
            streamedContentMessageIds.add(ANON_CONTENT_KEY);
            emitter.contentDelta(assistantContent, answer.trim());
        }
        boolean nothingStreamed = streamedContentMessageIds.isEmpty()
                && streamedThinkingMessageIds.isEmpty() && seenToolUseIds.isEmpty();
        if (failed && nothingStreamed && !errorEmitted) {
            String errorMessage = extractErrorText(
                    nested != null ? nested.get("error") : null,
                    nested != null ? nested.get("message") : null,
                    event.get("error"), event.get("message"));
            if (errorMessage.isEmpty()) {
                errorMessage = "MiniMax CLI run failed (status: " + status + ")";
            }
            emitErrorOnce(errorMessage);
        }
    }

    private void emitErrorOnce(String errorMessage) {
        hasError = true;
        errorEmitted = true;
        errorDiagnostic.append(errorMessage.trim());
        callback.onError(errorMessage.trim());
    }

    private void emitToolUseOnce(String toolCallId, String name, JsonElement rawInput) {
        if (!seenToolUseIds.add(toolCallId)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", toolCallId);
        block.addProperty("name", name);
        block.add("input", parseToolArguments(rawInput));
        emitter.toolUse(block);
    }

    private void emitToolResultOnce(String toolCallId, String output) {
        if (!seenToolResultIds.add(toolCallId)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolCallId);
        block.addProperty("content", output);
        emitter.toolResult(block);
    }

    private void handleMessage(JsonObject event) {
        JsonObject message = asObject(event, "message");
        if (message == null || !"assistant".equals(primitiveString(message.get("role")))) {
            return;
        }
        JsonObject usage = asObject(message, "usage");
        if (usage != null) {
            emitter.usage(gson.toJson(usage));
        }
        // 安全网:finished message 未流过任何 delta 时一次性补发全量(非流式回退)。
        String msgId = primitiveString(message.get("id"));
        if (msgId == null || msgId.isEmpty()) {
            return;
        }
        String content = primitiveString(message.get("content"));
        if (content != null && !content.isEmpty() && streamedContentMessageIds.add(msgId)) {
            emitter.contentDelta(assistantContent, content);
        }
        String thinking = primitiveString(message.get("thinking"));
        if (thinking != null && !thinking.isEmpty() && streamedThinkingMessageIds.add(msgId)) {
            if (!thinkingActivated) {
                thinkingActivated = true;
                emitter.thinkingStart();
            }
            emitter.thinkingDelta(thinking);
        }
    }

    /** tool output 结构化内容转文本(对称上游 extractToolOutputText)。 */
    private static String extractToolOutputText(JsonElement output) {
        if (output == null || output.isJsonNull()) {
            return "";
        }
        if (output.isJsonPrimitive()) {
            return output.getAsJsonPrimitive().isString() ? output.getAsString() : output.toString();
        }
        if (output.isJsonObject()) {
            JsonElement content = output.getAsJsonObject().get("content");
            if (content != null && content.isJsonPrimitive()) {
                return content.getAsString();
            }
            if (content != null && content.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonPrimitive()) {
                        sb.append(part.getAsString());
                    } else if (part.isJsonObject()) {
                        String text = primitiveString(part.getAsJsonObject().get("text"));
                        if (text != null) {
                            sb.append(text);
                        }
                    }
                }
                return sb.toString();
            }
        }
        try {
            return GsonHolder.GSON.toJson(output);
        } catch (Exception e) {
            return String.valueOf(output);
        }
    }

    /** tool_call input 归一:对象直传;字符串尝试 JSON.parse,失败包 {raw};空 → {}。 */
    private static JsonElement parseToolArguments(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return new JsonObject();
        }
        if (raw.isJsonObject()) {
            return raw;
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("value", raw.toString());
            return wrapper;
        }
        String trimmed = raw.getAsString().trim();
        if (trimmed.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = GsonHolder.GSON.fromJson(trimmed, JsonElement.class);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed;
            }
            JsonObject wrapper = new JsonObject();
            wrapper.add("value", parsed != null ? parsed : raw);
            return wrapper;
        } catch (Exception e) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("raw", trimmed);
            return wrapper;
        }
    }

    private static JsonObject asObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    /**
     * toolCall.status 归一(对称上游 Number(call.status)):数值或数字字符串均映射,
     * 其余(含 4/5 排队/运行中)返回 -1 由调用方忽略。
     */
    private static int normalizedToolStatus(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return -1;
        }
        try {
            return el.getAsJsonPrimitive().isNumber()
                    ? el.getAsInt()
                    : Integer.parseInt(el.getAsString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 流过去重键:有 id 用 id,无 id 退同类匿名键(对称上游 messageId || ANON_*_KEY)。 */
    private static String dedupKey(String id, String anonKey) {
        return id != null && !id.isEmpty() ? id : anonKey;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    /**
     * 错误文本提取(对称上游 extractErrorText):字符串直接取;对象取 {@code message} 字段;
     * 依次尝试候选,全空返回空串。
     */
    private static String extractErrorText(JsonElement... candidates) {
        for (JsonElement candidate : candidates) {
            if (candidate == null || candidate.isJsonNull()) {
                continue;
            }
            if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
                String text = candidate.getAsString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            } else if (candidate.isJsonObject()) {
                String text = primitiveString(candidate.getAsJsonObject().get("message"));
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private static String primitiveString(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
