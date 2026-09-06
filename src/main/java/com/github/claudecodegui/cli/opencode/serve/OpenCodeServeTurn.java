package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.opencode.OpenCodeEventMapper;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.asObject;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.buildToolResultBlock;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.buildToolUseBlock;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.buildUsage;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.deltaOf;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.extractErrorMessage;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.getString;

/**
 * opencode serve SSE 事件 → 统一 MSG_* 协议的轮级有状态映射
 * (对称 one-shot 的 {@code OpenCodeCliStreamParser},纯函数映射复用 {@link OpenCodeEventMapper})。
 * <p>
 * 每轮 send 构造一个新实例,持有本轮全部可变状态。事件映射(实测契约 opencode v1.18.26):
 * <ul>
 *   <li>{@code message.part.delta}(field=text, part type=text) → content_delta(token 级增量);
 *       part type=reasoning 的 delta 忽略——reasoning 内容由 part.updated 全量 +
 *       {@link OpenCodeEventMapper#deltaOf} 去重下发(对齐 one-shot 累积式,避免双发);</li>
 *   <li>{@code message.part.updated} → partID→type 映射;text part 只登记不发射
 *       (内容走 part.delta,防双发);reasoning part → thinking 增量;tool part →
 *       tool_use 块(首次)+ tool_result 块(state 终态 completed/error 时一次);</li>
 *   <li>{@code message.updated}(info.time.completed 且带 tokens) → MSG_RESULT(归一 usage,
 *       按 messageID 去重);info.error → 轮失败;</li>
 *   <li>{@code session.idle} → stream_end + message_end,轮完成;</li>
 *   <li>{@code session.error}:name=MessageAbortedError → 中断收尾;其余 → 轮失败;</li>
 *   <li>噪声白名单:server.connected/heartbeat、session.status、permission.asked
 *       (由会话层拦截应答)等一律忽略。</li>
 * </ul>
 * part.delta 先于 part.updated 到达(type 未知)时保守按 text 处理(实测 updated 先到)。
 */
final class OpenCodeServeTurn {

    // serve SSE 事件类型(opencode 外部协议契约,对称 one-shot parser 的 EVENT_* 常量)
    static final String EVENT_PART_DELTA = "message.part.delta";
    static final String EVENT_PART_UPDATED = "message.part.updated";
    static final String EVENT_MESSAGE_UPDATED = "message.updated";
    static final String EVENT_SESSION_IDLE = "session.idle";
    static final String EVENT_SESSION_ERROR = "session.error";
    static final String EVENT_PERMISSION_ASKED = "permission.asked";

    /** abort 后 serve 发出的 session.error 名(契约实测值)。 */
    static final String ERROR_MESSAGE_ABORTED = "MessageAbortedError";

    private static final String PART_TYPE_TEXT = "text";
    private static final String PART_TYPE_REASONING = "reasoning";
    private static final String PART_TYPE_TOOL = "tool";
    private static final String FIELD_TEXT = "text";
    private static final String STATE_COMPLETED = "completed";
    private static final String STATE_ERROR = "error";

    enum OutcomeKind {COMPLETED, FAILED, INTERRUPTED}

    record TurnResult(OutcomeKind kind, String error) {
    }

    private final CliSectionEmitter emitter;
    private final CompletableFuture<TurnResult> completion = new CompletableFuture<>();

    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder reasoningText = new StringBuilder();
    private final Map<String, String> partTypes = new HashMap<>();
    private final Set<String> toolUseEmitted = new HashSet<>();
    private final Set<String> toolResultEmitted = new HashSet<>();
    private final Set<String> usageEmittedMessages = new HashSet<>();
    private boolean streamStarted;
    private boolean thinkingActivated;
    private volatile boolean interrupted;

    OpenCodeServeTurn(CliSessionCallback callback) {
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

    /** SSE 流断开:已标记中断按中断收尾,否则当轮失败(不重连、已递交轮不重发)。 */
    void onStreamClosed(String reason) {
        if (interrupted) {
            complete(new TurnResult(OutcomeKind.INTERRUPTED, null));
        } else {
            complete(new TurnResult(OutcomeKind.FAILED, "opencode serve SSE stream closed: " + reason));
        }
    }

    void onEvent(JsonObject event) {
        if (completion.isDone()) {
            return;
        }
        String type = getString(event, "type");
        JsonObject properties = asObject(event, "properties");
        if (type == null || properties == null) {
            return;
        }
        switch (type) {
            case EVENT_PART_DELTA -> handlePartDelta(properties);
            case EVENT_PART_UPDATED -> handlePartUpdated(properties);
            case EVENT_MESSAGE_UPDATED -> handleMessageUpdated(properties);
            case EVENT_SESSION_IDLE -> handleSessionIdle();
            case EVENT_SESSION_ERROR -> handleSessionError(properties);
            default -> {
                // 噪声白名单:server.connected / server.heartbeat / session.status /
                // permission.asked(会话层拦截)/ file.watcher.updated 等一律忽略
            }
        }
    }

    // ── 事件处理 ──────────────────────────────────────────────────────────────

    private void handlePartDelta(JsonObject properties) {
        if (!FIELD_TEXT.equals(getString(properties, "field"))) {
            return;
        }
        String partId = getString(properties, "partID");
        String partType = partId != null ? partTypes.get(partId) : null;
        if (PART_TYPE_REASONING.equals(partType) || PART_TYPE_TOOL.equals(partType)) {
            // reasoning 内容走 part.updated 全量 + deltaOf 去重;tool 输入增量不下发
            return;
        }
        String delta = getString(properties, "delta");
        if (delta == null || delta.isEmpty()) {
            return;
        }
        ensureStreamStart();
        emitter.contentDelta(assistantContent, delta);
    }

    private void handlePartUpdated(JsonObject properties) {
        JsonObject part = asObject(properties, "part");
        if (part == null) {
            return;
        }
        String partId = getString(part, "id");
        String partType = getString(part, "type");
        if (partId == null || partType == null) {
            return;
        }
        partTypes.put(partId, partType);
        switch (partType) {
            case PART_TYPE_TEXT -> {
                // 内容走 part.delta(token 级);此处只登记映射,防与 delta 双发
            }
            case PART_TYPE_REASONING -> handleReasoningPart(part);
            case PART_TYPE_TOOL -> handleToolPart(part);
            default -> {
                // step-start / patch / file 等:只登记映射
            }
        }
    }

    /** reasoning part:全量累积 text 经 deltaOf 去重后下发(对称 one-shot handleReasoning)。 */
    private void handleReasoningPart(JsonObject part) {
        String delta = deltaOf(reasoningText.toString(), getString(part, FIELD_TEXT));
        if (!thinkingActivated) {
            thinkingActivated = true;
            emitter.thinkingStart();
        }
        if (delta != null) {
            ensureStreamStart();
            String accepted = CliOutputLimits.appendBounded(
                    reasoningText, delta, CliOutputLimits.MAX_REASONING_CHARS);
            if (!accepted.isEmpty()) {
                emitter.thinkingDelta(accepted);
            }
        }
    }

    /** tool part:首次见发 tool_use 块;state 达终态(completed/error)发一次 tool_result 块。 */
    private void handleToolPart(JsonObject part) {
        String partId = getString(part, "id");
        if (toolUseEmitted.add(partId)) {
            ensureStreamStart();
            emitter.toolUse(buildToolUseBlock(part));
        }
        JsonObject state = asObject(part, "state");
        String status = state != null ? getString(state, "status") : null;
        boolean terminal = STATE_COMPLETED.equalsIgnoreCase(status) || STATE_ERROR.equalsIgnoreCase(status)
                || OpenCodeEventMapper.isErrorState(state);
        if (terminal && toolResultEmitted.add(partId)) {
            emitter.toolResult(buildToolResultBlock(part));
        }
    }

    private void handleMessageUpdated(JsonObject properties) {
        JsonObject info = asObject(properties, "info");
        if (info == null) {
            return;
        }
        JsonObject error = asObject(info, "error");
        if (error != null) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("error", error);
            complete(new TurnResult(OutcomeKind.FAILED, extractErrorMessage(wrapper)));
            return;
        }
        // usage 经 MSG_RESULT 下发(对称 one-shot step_finish;按 messageID 去重,仅 completed 后)
        JsonObject time = asObject(info, "time");
        if (time == null || time.get("completed") == null || time.get("completed").isJsonNull()) {
            return;
        }
        JsonObject tokens = asObject(info, "tokens");
        String messageId = getString(info, "id");
        if (tokens != null && messageId != null && usageEmittedMessages.add(messageId)) {
            JsonObject resultWrapper = new JsonObject();
            resultWrapper.add("usage", buildUsage(tokens));
            emitter.result(resultWrapper.toString());
        }
    }

    private void handleSessionIdle() {
        if (streamStarted) {
            emitter.streamEnd();
            emitter.messageEnd();
        }
        complete(new TurnResult(OutcomeKind.COMPLETED, null));
    }

    private void handleSessionError(JsonObject properties) {
        JsonObject error = asObject(properties, "error");
        if (error != null && ERROR_MESSAGE_ABORTED.equals(getString(error, "name"))) {
            interrupted = true;
            complete(new TurnResult(OutcomeKind.INTERRUPTED, null));
            return;
        }
        complete(new TurnResult(OutcomeKind.FAILED, extractErrorMessage(properties)));
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
}
