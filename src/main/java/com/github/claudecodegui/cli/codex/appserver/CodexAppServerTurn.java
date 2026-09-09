package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * codex app-server 通知 → 统一 MSG_* 协议的轮级有状态映射
 * (对称 one-shot 的 {@code CodexCliSession.parseEvent};事件契约经 codex-cli 0.153.4
 * generate-json-schema 校验,item.type 为 camelCase,区别于 exec --json 的 snake_case)。
 * <p>
 * 每轮 send 构造一个新实例,持有本轮全部可变状态。映射规则(实测):
 * <ul>
 *   <li>{@code item/agentMessage/delta} → content_delta(<b>token 级增量,立即下发不缓冲</b>——
 *       这是 app-server 通道存在的核心目的,exec --json 无此事件);</li>
 *   <li>{@code item/reasoning/summaryTextDelta} / {@code item/reasoning/textDelta} →
 *       thinking 首启 + thinking_delta(推理摘要流);无 delta 流的部署(custom provider
 *       实测)推理以 {@code item/completed} 整块到达,由 reasoning 完成块去重补差兜底,
 *       保证推理期即产出 thinking block(状态卡随之消失,对齐其他 provider);</li>
 *   <li>{@code item/started}(commandExecution / mcpToolCall)→ tool_use 块一次;</li>
 *   <li>{@code item/completed}:agentMessage → 与已累积 delta 对账补差(防丢尾);
 *       commandExecution / mcpToolCall → tool_result 块一次;fileChange / webSearch →
 *       归一化 assistantRaw 块;</li>
 *   <li>{@code thread/tokenUsage/updated} → usage + result(映射 Claude schema,
 *       按 turnId 去重,取 last 即本轮最新模型用量);</li>
 *   <li>{@code turn/completed}(turn.status)→ completed / interrupted / failed 收尾。</li>
 * </ul>
 * 噪声(thread/started、mcpServer/startupStatus、account/* 等)一律忽略。
 */
final class CodexAppServerTurn {

    enum OutcomeKind {COMPLETED, FAILED, INTERRUPTED}

    record TurnResult(OutcomeKind kind, String error) {
    }

    private final CliSectionEmitter emitter;
    private final CompletableFuture<TurnResult> completion = new CompletableFuture<>();

    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder reasoningText = new StringBuilder();
    /** 已经 summaryTextDelta/textDelta 流式输出的 reasoning item(delta 流为权威,完成块跳过)。 */
    private final Set<String> reasoningDeltaStreamedItems = new HashSet<>();
    private final Set<String> toolUseEmitted = new HashSet<>();
    private final Set<String> toolResultEmitted = new HashSet<>();
    private final Set<String> usageEmittedTurns = new HashSet<>();
    private boolean streamStarted;
    private boolean thinkingActivated;
    private volatile boolean interrupted;

    CodexAppServerTurn(CliSessionCallback callback) {
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    CompletableFuture<TurnResult> completion() {
        return completion;
    }

    String accumulatedText() {
        return assistantContent.toString();
    }

    void markInterrupted() {
        interrupted = true;
    }

    boolean wasInterrupted() {
        return interrupted;
    }

    /** app-server 进程死亡 / 流关闭:已标记中断按中断收尾,否则当轮失败(不重建、已递交轮不重发)。 */
    void onProcessDied(@NotNull String reason) {
        if (interrupted) {
            complete(new TurnResult(OutcomeKind.INTERRUPTED, null));
        } else {
            complete(new TurnResult(OutcomeKind.FAILED, "codex app-server process died: " + reason));
        }
    }

    /** server 通知入口(message 含 method/params,已按 threadId 路由到本实例)。 */
    void onNotification(@NotNull JsonObject notification) {
        if (completion.isDone()) {
            return;
        }
        String method = getString(notification, "method");
        if (method == null) {
            return;
        }
        JsonObject params = asObject(notification, "params");
        switch (method) {
            case CliConstants.CODEX_APPSERVER_NOTIFY_AGENT_MESSAGE_DELTA -> handleAgentMessageDelta(params);
            case CliConstants.CODEX_APPSERVER_NOTIFY_REASONING_SUMMARY_DELTA,
                    CliConstants.CODEX_APPSERVER_NOTIFY_REASONING_TEXT_DELTA -> handleReasoningDelta(params);
            case CliConstants.CODEX_APPSERVER_NOTIFY_ITEM_STARTED -> handleItem(asObject(params, "item"), false);
            case CliConstants.CODEX_APPSERVER_NOTIFY_ITEM_COMPLETED -> handleItem(asObject(params, "item"), true);
            case CliConstants.CODEX_APPSERVER_NOTIFY_TOKEN_USAGE -> handleTokenUsage(params);
            case CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED -> handleTurnCompleted(asObject(params, "turn"));
            default -> {
                // 噪声白名单:thread/started、thread/status/changed、turn/started、
                // mcpServer/startupStatus/updated、account/*、item/*/outputDelta 等一律忽略
            }
        }
    }

    // ── 事件处理 ──────────────────────────────────────────────────────────────

    /** agent 正文 token 级增量:立即下发,绝不缓冲到 completed(硬不变量,对称 one-shot)。 */
    private void handleAgentMessageDelta(JsonObject params) {
        String delta = params != null ? getString(params, "delta") : null;
        if (delta == null || delta.isEmpty()) {
            return;
        }
        ensureStreamStart();
        emitter.contentDelta(assistantContent, delta);
    }

    private void handleReasoningDelta(JsonObject params) {
        String delta = params != null ? getString(params, "delta") : null;
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String itemId = params.has("itemId") && !params.get("itemId").isJsonNull()
                ? params.get("itemId").getAsString() : null;
        if (itemId != null) {
            reasoningDeltaStreamedItems.add(itemId);
        }
        emitReasoningDelta(delta);
    }

    /**
     * reasoning 完成块:summary(缺省 content)数组拼接后与已累积文本去重,仅补差下发。
     * <p>
     * 部分部署(实测:custom provider 的 gpt-6-astra,未开 summary 流)推理只以
     * item/completed 整块到达、没有 summaryTextDelta;无此映射则整个推理窗口无
     * thinking block,连接状态卡会滞留到首个正文 delta(RESPONDING 卡露出于等待期,
     * 与其他 provider「推理即出 thinking block、状态卡随之消失」不一致)。
     * <p>
     * 已流式输出过的 item( itemId 见 {@link #reasoningDeltaStreamedItems})直接跳过:
     * 完成块不携带新信息,且块拼接分隔符与 delta 流的分段换行不一致(实测
     * "\n\n" join vs delta 原样换行),前缀去重必然失效会造成整段重复下发。
     * 块全文 startsWith 已累积 → 只补差;独立新段(多 reasoning item)→ 全文追加。
     */
    private void handleReasoningItemCompleted(JsonObject item) {
        String itemId = getString(item, "id");
        if (itemId != null && reasoningDeltaStreamedItems.contains(itemId)) {
            return;
        }
        String blockText = joinReasoningText(item, "summary");
        if (blockText == null || blockText.isEmpty()) {
            blockText = joinReasoningText(item, "content");
        }
        if (blockText == null || blockText.isEmpty()) {
            return;
        }
        String delta = reasoningDeltaOf(reasoningText.toString(), blockText);
        if (delta != null) {
            emitReasoningDelta(delta);
        }
    }

    /** 推理文本数组(summary / content)拼接,段间空行分隔;非字符串元素忽略。 */
    private static String joinReasoningText(JsonObject item, String field) {
        if (!item.has(field) || !item.get(field).isJsonArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (var element : item.getAsJsonArray(field)) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String segment = element.getAsString();
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    /** 去重:块全文 startsWith 已累积 → 补差;相同 → null;独立新段 → 全文。 */
    private static String reasoningDeltaOf(String previous, String next) {
        String oldText = previous == null ? "" : previous;
        String newText = next == null ? "" : next;
        if (newText.isEmpty() || newText.equals(oldText)) {
            return null;
        }
        if (oldText.isEmpty() || newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    private void emitReasoningDelta(String delta) {
        if (!thinkingActivated) {
            thinkingActivated = true;
            emitter.thinkingStart();
        }
        ensureStreamStart();
        String accepted = CliOutputLimits.appendBounded(
                reasoningText, delta, CliOutputLimits.MAX_REASONING_CHARS);
        if (!accepted.isEmpty()) {
            emitter.thinkingDelta(accepted);
        }
    }

    private void handleItem(@Nullable JsonObject item, boolean completed) {
        if (item == null) {
            return;
        }
        String id = firstNonBlank(getString(item, "id"), "fallback-" + System.nanoTime());
        String type = getString(item, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case CliConstants.CODEX_APPSERVER_ITEM_AGENT_MESSAGE -> {
                if (completed) {
                    reconcileAgentMessage(id, getString(item, "text"));
                }
            }
            case CliConstants.CODEX_APPSERVER_ITEM_REASONING -> {
                if (completed) {
                    handleReasoningItemCompleted(item);
                }
            }
            case CliConstants.CODEX_APPSERVER_ITEM_COMMAND_EXECUTION -> handleCommandExecution(id, item, completed);
            case CliConstants.CODEX_APPSERVER_ITEM_MCP_TOOL_CALL -> handleMcpToolCall(id, item, completed);
            case CliConstants.CODEX_APPSERVER_ITEM_FILE_CHANGE -> {
                if (completed) {
                    emitFileChangeBlock(id, item);
                }
            }
            case CliConstants.CODEX_APPSERVER_ITEM_WEB_SEARCH -> {
                if (completed) {
                    emitWebSearchBlock(id, item);
                }
            }
            default -> {
                // plan / userMessage / 其他:无额外动作
            }
        }
    }

    /**
     * completed 的 agentMessage 全文与已累积 delta 对账:completed 文本更长则补差下发
     * (防 delta 丢失截尾);否则无事(delta 已覆盖)。
     */
    private void reconcileAgentMessage(String id, String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        String accumulated = assistantContent.toString();
        if (fullText.length() > accumulated.length() && fullText.startsWith(accumulated)) {
            ensureStreamStart();
            emitter.contentDelta(assistantContent, fullText.substring(accumulated.length()));
        }
    }

    private void handleCommandExecution(String id, JsonObject item, boolean completed) {
        if (!completed) {
            if (toolUseEmitted.add(id)) {
                ensureStreamStart();
                JsonObject input = new JsonObject();
                input.addProperty("command", getString(item, "command"));
                emitter.assistantRaw(buildToolUseMessage(id, "Bash", input));
            }
            return;
        }
        if (toolResultEmitted.add(id)) {
            if (toolUseEmitted.add(id)) {
                // completed 先于 started 到达的防御:补发 tool_use
                JsonObject input = new JsonObject();
                input.addProperty("command", getString(item, "command"));
                emitter.assistantRaw(buildToolUseMessage(id, "Bash", input));
            }
            boolean error = item.has("exitCode") && !item.get("exitCode").isJsonNull()
                    && item.get("exitCode").getAsInt() != 0;
            emitter.userRaw(buildToolResultMessage(id, error, getString(item, "aggregatedOutput")));
        }
    }

    private void handleMcpToolCall(String id, JsonObject item, boolean completed) {
        String server = getString(item, "server");
        String tool = getString(item, "tool");
        String toolName = normalizeMcpToolName(server, tool);
        if (!completed) {
            if (toolUseEmitted.add(id)) {
                ensureStreamStart();
                JsonObject input = asObject(item, "arguments");
                emitter.assistantRaw(buildToolUseMessage(id, toolName, input != null ? input : new JsonObject()));
            }
            return;
        }
        if (toolResultEmitted.add(id)) {
            if (toolUseEmitted.add(id)) {
                JsonObject input = asObject(item, "arguments");
                emitter.assistantRaw(buildToolUseMessage(id, toolName, input != null ? input : new JsonObject()));
            }
            boolean error = item.has("error") && !item.get("error").isJsonNull();
            String resultText = error ? getString(item, "error")
                    : stringify(asObject(item, "result"));
            emitter.userRaw(buildToolResultMessage(id, error, resultText));
        }
    }

    private void handleTokenUsage(JsonObject params) {
        if (params == null) {
            return;
        }
        String turnId = getString(params, "turnId");
        if (turnId == null || !usageEmittedTurns.add(turnId)) {
            return;
        }
        JsonObject tokenUsage = asObject(params, "tokenUsage");
        JsonObject last = tokenUsage != null ? asObject(tokenUsage, "last") : null;
        if (last == null) {
            return;
        }
        JsonObject mappedUsage = new JsonObject();
        mappedUsage.addProperty("input_tokens", getIntOrZero(last, "inputTokens"));
        mappedUsage.addProperty("output_tokens", getIntOrZero(last, "outputTokens"));
        mappedUsage.addProperty("cache_creation_input_tokens", getIntOrZero(last, "cacheWriteInputTokens"));
        mappedUsage.addProperty("cache_read_input_tokens", getIntOrZero(last, "cachedInputTokens"));
        emitter.usage(mappedUsage.toString());

        JsonObject resultWrapper = new JsonObject();
        resultWrapper.add("usage", mappedUsage);
        emitter.result(resultWrapper.toString());
    }

    private void handleTurnCompleted(JsonObject turn) {
        String status = turn != null ? getString(turn, "status") : null;
        if (CliConstants.CODEX_APPSERVER_TURN_STATUS_INTERRUPTED.equals(status)) {
            interrupted = true;
            complete(new TurnResult(OutcomeKind.INTERRUPTED, null));
            return;
        }
        if (CliConstants.CODEX_APPSERVER_TURN_STATUS_FAILED.equals(status)) {
            complete(new TurnResult(OutcomeKind.FAILED, extractTurnError(turn)));
            return;
        }
        if (streamStarted) {
            emitter.streamEnd();
            emitter.messageEnd();
        }
        complete(new TurnResult(OutcomeKind.COMPLETED, null));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private void ensureStreamStart() {
        if (!streamStarted) {
            streamStarted = true;
            emitter.streamStart();
        }
    }

    private void complete(TurnResult result) {
        completion.complete(result);
    }

    /** 会话层补发流结束(超时 / 失败路径有内容但缺收尾时,对称 one-shot 的补发逻辑)。 */
    void emitStreamEndIfStarted() {
        if (streamStarted) {
            emitter.streamEnd();
            emitter.messageEnd();
        }
    }

    private static String extractTurnError(JsonObject turn) {
        JsonObject error = asObject(turn, "error");
        String message = error != null ? getString(error, "message") : null;
        return message != null ? message : "codex app-server turn failed";
    }

    /** tool_use 块(形状对齐 one-shot CodexCliSession.buildToolUseMessage,CodexMessageHandler 消费)。 */
    private static JsonObject buildToolUseMessage(String id, String name, JsonObject input) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", id);
        block.addProperty("name", name);
        block.add("input", input != null ? input : new JsonObject());

        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject message = new JsonObject();
        message.add("content", content);
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("message", message);
        return raw;
    }

    /** tool_result 块(形状对齐 one-shot CodexCliSession.buildToolResultMessage)。 */
    private static JsonObject buildToolResultMessage(String id, boolean isError, String contentText) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", id);
        block.addProperty("is_error", isError);
        block.addProperty("content", contentText == null || contentText.isBlank()
                ? "(no output)" : contentText);

        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject message = new JsonObject();
        message.add("content", content);
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        raw.add("message", message);
        return raw;
    }

    private void emitFileChangeBlock(String id, JsonObject item) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "file_change");
        block.addProperty("id", id);
        block.add("changes", item.has("changes") && item.get("changes").isJsonArray()
                ? item.get("changes").getAsJsonArray() : new JsonArray());
        block.addProperty("status", getString(item, "status"));
        emitter.assistantRaw(wrapBlockAsAssistant(block));
    }

    private void emitWebSearchBlock(String id, JsonObject item) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "web_search");
        block.addProperty("id", id);
        block.addProperty("query", getString(item, "query"));
        block.addProperty("action", getString(item, "action"));
        block.add("results", item.has("results") && item.get("results").isJsonArray()
                ? item.get("results").getAsJsonArray() : new JsonArray());
        emitter.assistantRaw(wrapBlockAsAssistant(block));
    }

    private static JsonObject wrapBlockAsAssistant(JsonObject block) {
        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject message = new JsonObject();
        message.add("content", content);
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("message", message);
        return raw;
    }

    /** mcp__server__tool(对齐 one-shot normalizeMcpToolName;缺段保守占位)。 */
    private static String normalizeMcpToolName(String server, String tool) {
        String normalizedServer = server != null && !server.isBlank() ? server.trim() : "unknown-server";
        String normalizedTool = tool != null && !tool.isBlank() ? tool.trim() : "unknown-tool";
        return "mcp__" + normalizedServer + "__" + normalizedTool;
    }

    private static String stringify(JsonObject value) {
        return value == null ? null : value.toString();
    }

    private static int getIntOrZero(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static @Nullable String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(key).getAsString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable JsonObject asObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
