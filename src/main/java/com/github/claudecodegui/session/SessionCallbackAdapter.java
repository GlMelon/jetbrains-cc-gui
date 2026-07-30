package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.permission.PermissionActionHandlers;
import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.JsUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.Alarm;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Named implementation of ClaudeSession.SessionCallback.
 * Replaces the large anonymous inner class in setupSessionCallbacks().
 * Delegates streaming events to StreamMessageCoalescer and UI events to JavaScript callbacks.
 */
public class SessionCallbackAdapter implements ClaudeSession.SessionCallback {

    private static final Logger LOG = Logger.getInstance(SessionCallbackAdapter.class);
    /** Throttle interval targeting ~30fps to balance responsiveness with UI thread load. */
    private static final int DELTA_THROTTLE_MS = 33;

    /**
     * Callback interface for JavaScript calls from session events.
     */
    public interface JsTarget {
        void callJavaScript(String functionName, String... args);

        default void dispatchEvent(String type, String payloadJson) {
            callJavaScript("window.__bridge.dispatch", type, payloadJson == null ? "" : payloadJson);
        }
    }

    private final StreamMessageCoalescer streamCoalescer;
    private final JsTarget jsTarget;
    private final PermissionActionHandlers permissionHandler;
    private final BooleanSupplier slashCommandsFetchedSupplier;
    private final Runnable streamEndCallback;
    private final StreamDeltaThrottler contentDeltaThrottler;
    private final StreamDeltaThrottler thinkingDeltaThrottler;
    /**
     * 统一推送层开关:让"流式输出"与"思考区"两 toggle 对所有 provider/调用模式(SDK/CLI)生效。
     * 流式 off → content delta 进 per-turn buffer,turn/段边界一次性 flush 全量;
     * 思考区 off → 丢弃 thinking delta 与 thinking-status(模型照常思考,只控显示)。
     * 纯逻辑组件,单测见 {@link TurnPushGateTest}。
     */
    private final TurnPushGate turnPushGate;
    private final Alarm streamEndFallbackAlarm;
    private volatile boolean active = true;
    /** Guards against duplicate onStreamEnd delivery from dual-path dispatch. */
    private volatile boolean streamEndSignalSent = false;
    private volatile String responseStatusProviderLabel = null;
    private volatile long responseStatusTurnStartedAtMillis = 0L;
    private volatile boolean thinkingPhaseSent = false;
    private volatile boolean respondingPhaseSent = false;
    private volatile boolean toolingPhaseSent = false;
    /**
     * 单调递增的"流轮次"令牌。onStreamStart 递增,onStreamEnd 的双路径(primary flush 回调 +
     * 300ms Alarm 回退)都捕获并校验当前令牌。当上一轮的 flush 回调因慢速 JCEF IPC 延迟到
     * 本轮才执行时,令牌不匹配使其被丢弃,避免用上一轮的 stale sequence 向前端误发 streamEnd
     * (会提前结束本轮正在进行的流)。此前仅靠 streamEndSignalSent 单布尔守卫,无法区分跨轮次。
     */
    private volatile long streamEndTurn = 0L;

    public SessionCallbackAdapter(
            StreamMessageCoalescer streamCoalescer,
            JsTarget jsTarget,
            PermissionActionHandlers permissionHandler,
            BooleanSupplier slashCommandsFetchedSupplier,
            Runnable streamEndCallback,
            BooleanSupplier streamingEnabledSupplier,
            BooleanSupplier showThinkingEnabledSupplier
    ) {
        this.streamCoalescer = streamCoalescer;
        this.jsTarget = jsTarget;
        this.permissionHandler = permissionHandler;
        this.slashCommandsFetchedSupplier = slashCommandsFetchedSupplier;
        this.streamEndCallback = streamEndCallback;
        this.contentDeltaThrottler = new StreamDeltaThrottler(
                DELTA_THROTTLE_MS,
                delta -> {
                    if (!isInactive()) {
                        jsTarget.callJavaScript("onContentDelta", JsUtils.escapeJs(delta));
                    }
                }
        );
        this.thinkingDeltaThrottler = new StreamDeltaThrottler(
                DELTA_THROTTLE_MS,
                delta -> {
                    if (!isInactive()) {
                        jsTarget.callJavaScript("onThinkingDelta", JsUtils.escapeJs(delta));
                    }
                }
        );
        // 统一推送层开关:流式 off 时 content delta 由 gate 缓冲到 turn 边界一次性推送
        // (contentSink 直接 callJavaScript,不经 throttler——全量无需再节流);
        // 思考区 off 时 gate 在 notifyThinkingDelta/notifyThinkingStatusChanged 处丢弃。
        // 开关值在每个 turn 开始(onStreamStart)由 gate.onTurnStart 快照读取一次。
        this.turnPushGate = new TurnPushGate(
                delta -> {
                    if (!isInactive()) {
                        jsTarget.callJavaScript("onContentDelta", JsUtils.escapeJs(delta));
                    }
                },
                streamingEnabledSupplier,
                showThinkingEnabledSupplier
        );
        this.streamEndFallbackAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
    }

    public void deactivate() {
        active = false;
        contentDeltaThrottler.dispose();
        thinkingDeltaThrottler.dispose();
        streamEndFallbackAlarm.cancelAllRequests();
    }

    private boolean isInactive() {
        return !active;
    }

    @Override
    public void onMessageUpdate(List<ClaudeSession.Message> messages) {
        if (isInactive()) {
            return;
        }
        streamCoalescer.enqueue(messages);
    }

    @Override
    public void onStateChange(boolean busy, boolean loading, String error) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            // Do not send loading=false during streaming to avoid unexpected loading state resets.
            // State cleanup is handled uniformly by onStreamEnd.
            if (!loading && streamCoalescer.isStreamActive()) {
                LOG.debug("Suppressing showLoading(false) during active streaming");
                return;
            }

            jsTarget.callJavaScript("showLoading", String.valueOf(loading));
            // Show error in status bar only (not as toast) to avoid duplicate notifications.
            // The primary error display is the ERROR message in chat list (from onError path).
            if (error != null && !error.isEmpty()) {
                jsTarget.callJavaScript("updateStatus", JsUtils.escapeJs("Error: " + error));
            }
            if (!busy && !loading) {
                VirtualFileManager.getInstance().asyncRefresh(null);
            }
        });
    }

    @Override
    public void onStatusMessage(String message) {
        if (isInactive() || message == null || message.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("updateStatus", JsUtils.escapeJs(message));
        });
    }

    @Override
    public void onSessionIdReceived(String sessionId) {
        if (isInactive()) {
            return;
        }
        LOG.info("Session ID: " + sessionId);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("setSessionId", JsUtils.escapeJs(sessionId));
        });
    }

    @Override
    public void onPermissionRequested(PermissionRequest request) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            permissionHandler.showPermissionDialog(request);
        });
    }

    @Override
    public void onThinkingStatusChanged(boolean isThinking) {
        if (isInactive()) {
            return;
        }
        // 思考区 off → 抑制思考指示灯(避免模型思考时 UI 误亮"思考中")。
        if (!turnPushGate.shouldEmitThinking()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("showThinkingStatus", String.valueOf(isThinking));
            LOG.debug("Thinking status changed: " + isThinking);
        });
    }

    @Override
    public void onSlashCommandsReceived(List<String> slashCommands) {
        // No longer send old-format (string array) commands to the frontend.
        // Reasons:
        // 1. The full command list (with descriptions) was already fetched from getSlashCommands() during init.
        // 2. The commands received here are in old format (names only, no descriptions).
        // 3. Sending to frontend would overwrite the full command list, losing descriptions.
        int incomingCount = slashCommands != null ? slashCommands.size() : 0;
        LOG.debug("onSlashCommandsReceived called (old format, ignored). incoming=" + incomingCount);

        if (slashCommands != null && !slashCommands.isEmpty() && !slashCommandsFetchedSupplier.getAsBoolean()) {
            LOG.debug("Received " + incomingCount + " slash commands (old format), but keeping existing commands with descriptions");
        }
    }

    @Override
    public void onSummaryReceived(String summary) {
        LOG.debug("Summary received: " + (summary != null ? summary.substring(0, Math.min(50, summary.length())) : "null"));
        if (isInactive() || summary == null || summary.trim().isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("showSummary", JsUtils.escapeJs(summary));
        });
    }

    @Override
    public void onNodeLog(String log) {
        LOG.debug("Node log: " + (log != null ? log.substring(0, Math.min(100, log.length())) : "null"));
    }

    // ===== Streaming callback methods =====

    @Override
    public void onResponsePhase(AssistantResponseStatusPayload payload) {
        if (isInactive() || payload == null) {
            return;
        }
        if (AssistantResponsePhase.QUEUED.value().equals(payload.phase()) || responseStatusTurnStartedAtMillis <= 0L) {
            responseStatusTurnStartedAtMillis = System.currentTimeMillis();
        }
        if (payload.providerLabel() != null && !payload.providerLabel().trim().isEmpty()) {
            responseStatusProviderLabel = payload.providerLabel().trim();
        }
        sendResponsePhase(payload);
    }

    @Override
    public void onStreamStart() {
        if (isInactive()) {
            return;
        }
        if (responseStatusTurnStartedAtMillis <= 0L) {
            responseStatusTurnStartedAtMillis = System.currentTimeMillis();
        }
        thinkingPhaseSent = false;
        respondingPhaseSent = false;
        toolingPhaseSent = false;
        // 开启新一轮流:递增 turn 令牌,令上一轮残留的 stale streamEnd 双路径回调全部失效,
        // 避免它们用上一轮的 sequence 误发 streamEnd(会提前结束本轮流)。
        streamEndTurn++;
        // Cancel any stale fallback alarm from the previous turn to prevent
        // it from firing during the new turn's streaming phase.
        streamEndFallbackAlarm.cancelAllRequests();
        // 快照读取本 turn 的流式/思考区开关值,并清上一 turn 残留 buffer。
        // 中途切开关从下一个 turn 生效(整 turn 用同一快照值)。
        turnPushGate.onTurnStart();
        contentDeltaThrottler.reset();
        thinkingDeltaThrottler.reset();
        streamCoalescer.onStreamStart();
        sendResponsePhaseForCurrentTurn(AssistantResponsePhase.UNDERSTANDING);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("showLoading", "true");
            jsTarget.callJavaScript("onStreamStart");
            LOG.debug("Stream started - notified frontend with loading=true");
        });
    }

    @Override
    public void onStreamEnd() {
        if (isInactive()) {
            return;
        }
        // Reset the signal guard so this turn's dual-path dispatch can proceed.
        streamEndSignalSent = false;
        // 捕获本轮 token:flush 回调可能在慢速 JCEF 下延迟到下一轮才执行,
        // 届时 turn != streamEndTurn,该 stale 回调被丢弃,防止跨轮误发 streamEnd。
        final long turn = streamEndTurn;

        // Each step is wrapped in safeRun so that a failure in one step
        // (e.g., flushNow throwing due to a disposed throttler, or JCEF
        // rejecting a large payload) does not prevent the critical
        // onStreamEnd signal from reaching the frontend.
        // 流式 off 时先把 gate 缓冲的本 turn 全量 content 推给前端(先于 throttler 残留与 message 快照),
        // 保证前端在收到 stream-end 信号前已拿到完整内容。流式 on 时为 no-op(gate 未缓冲)。
        safeRun("turnPushGate.flushContent", turnPushGate::flushContent);
        safeRun("contentDeltaThrottler.flushNow", contentDeltaThrottler::flushNow);
        safeRun("thinkingDeltaThrottler.flushNow", thinkingDeltaThrottler::flushNow);
        safeRun("streamCoalescer.onStreamEnd", streamCoalescer::onStreamEnd);

        // ── Dual-path onStreamEnd delivery ──
        //
        // Primary path: chain onStreamEnd inside the flush callback. The callback
        // runs on the EDT *after* the updateMessages JS call has been dispatched,
        // guaranteeing the frontend receives the final message snapshot before the
        // stream-end signal.
        //
        // Fallback path: an independent Alarm fires after 300ms. This covers the
        // scenario where the flush's 3-layer async pipeline fails silently (JCEF
        // large payload rejection, disposed browser, JSON serialization OOM).
        //
        // The frontend's onStreamEnd is idempotent (per-turn guard), so receiving
        // both signals is harmless — only the first takes effect.

        // Primary: ordered delivery via flush callback
        streamCoalescer.flush(sequence -> {
            if (streamEndSignalSent || turn != streamEndTurn) {
                return;
            }
            streamEndSignalSent = true;
            streamEndFallbackAlarm.cancelAllRequests();
            sendStreamEndToFrontend(sequence);
        });

        // Fallback: independent delivery after timeout
        streamEndFallbackAlarm.cancelAllRequests();
        streamEndFallbackAlarm.addRequest(() -> {
            if (streamEndSignalSent || isInactive() || turn != streamEndTurn) {
                return;
            }
            streamEndSignalSent = true;
            LOG.warn("Stream end signal delivered via fallback (primary flush callback did not fire within 300ms)");
            sendStreamEndToFrontend(-1);
        }, 300);
    }

    @Override
    public void onStreamCompleted() {
        if (isInactive()) {
            return;
        }
        if (streamEndCallback != null) {
            safeRun("streamEndCallback", streamEndCallback);
        }
    }

    /**
     * Send the onStreamEnd signal and associated cleanup to the frontend.
     * Called from either the primary (flush callback) or fallback (Alarm) path.
     *
     * @param sequence the flush sequence number, or -1 if fired from fallback
     */
    private void sendStreamEndToFrontend(long sequence) {
        if (isInactive()) {
            LOG.debug("Skipping sendStreamEndToFrontend — adapter deactivated (sequence=" + sequence + ")");
            return;
        }
        sendResponsePhaseForCurrentTurn(AssistantResponsePhase.DONE);
        // Tag this as a backend-triggered stream-end so the frontend can
        // distinguish it from a stall-watchdog recovery in diagnostic logs.
        safeRun("callJavaScript(__lastStreamEndSource)", () ->
                jsTarget.callJavaScript("__lastStreamEndSource", "'backend'"));
        safeRun("callJavaScript(onStreamEnd)", () ->
                jsTarget.callJavaScript("onStreamEnd", String.valueOf(sequence)));
        safeRun("callJavaScript(showLoading, false)", () ->
                jsTarget.callJavaScript("showLoading", "false"));
        LOG.debug("Stream ended - notified frontend (sequence=" + sequence + ")");
        responseStatusTurnStartedAtMillis = 0L;
    }

    private void sendResponsePhaseForCurrentTurn(AssistantResponsePhase phase) {
        String providerLabel = responseStatusProviderLabel;
        AssistantResponseStatusPayload payload = AssistantResponseStatusPayload.forProviderLabel(
                phase,
                providerLabel,
                responseStatusTurnStartedAtMillis
        );
        sendResponsePhase(payload);
    }

    private void sendResponsePhase(AssistantResponseStatusPayload payload) {
        String json = GsonHolder.GSON.toJson(payload);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript(
                    "window.__bridge.dispatch",
                    DownstreamEvent.STREAM_RESPONSE_PHASE.value(),
                    JsUtils.escapeJs(json)
            );
        });
    }

    private static void safeRun(String label, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warn(label + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void onContentDelta(String delta) {
        if (isInactive()) {
            return;
        }
        if (!respondingPhaseSent) {
            respondingPhaseSent = true;
            sendResponsePhaseForCurrentTurn(AssistantResponsePhase.RESPONDING);
        }
        // 流式 on → gate 透传,交现有 throttler 增量节流;
        // 流式 off → gate 缓冲到 per-turn buffer,不下发 throttler,turn/段边界一次性 flush 全量。
        if (turnPushGate.onContentDelta(delta)) {
            contentDeltaThrottler.append(delta);
        }
    }

    @Override
    public void onThinkingDelta(String delta) {
        if (isInactive()) {
            return;
        }
        if (!thinkingPhaseSent) {
            thinkingPhaseSent = true;
            sendResponsePhaseForCurrentTurn(AssistantResponsePhase.THINKING);
        }
        // 思考区 off → 丢弃 thinking delta(模型照常思考,只控推送/显示);
        // 思考区 on → 透传现有 throttler。
        if (!turnPushGate.shouldEmitThinking()) {
            return;
        }
        thinkingDeltaThrottler.append(delta);
    }

    @Override
    public void onBlockReset() {
        if (isInactive()) {
            return;
        }
        if (!toolingPhaseSent) {
            toolingPhaseSent = true;
            sendResponsePhaseForCurrentTurn(AssistantResponsePhase.TOOLING);
        }
        // 段边界(tool-use 循环):先 flush 本段所有缓冲,再 reset throttlers。
        // 流式 on 时最后一批 delta 可能仍在 33ms throttler 中;直接 reset 会丢失段尾文本。
        // 流式 off 时 content delta 由 turnPushGate 缓冲。每段分别 flush,避免跨 tool-use 块串成一段。
        safeRun("turnPushGate.flushContent", turnPushGate::flushContent);
        safeRun("contentDeltaThrottler.flushNow", contentDeltaThrottler::flushNow);
        safeRun("thinkingDeltaThrottler.flushNow", thinkingDeltaThrottler::flushNow);
        // Reset throttler timestamps for the next block after pending deltas have been delivered.
        contentDeltaThrottler.reset();
        thinkingDeltaThrottler.reset();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onBlockReset");
            LOG.debug("Block reset sent to frontend - streaming refs cleared");
        });
    }

    @Override
    public void onUsageUpdate(int usedTokens, int maxTokens) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            int safeUsedTokens = normalizeUsageValue(usedTokens);
            int safeMaxTokens = normalizeUsageValue(maxTokens);
            double percentage = calculateUsagePercentage(safeUsedTokens, safeMaxTokens);
            String json = String.format("{\"percentage\":%.2f,\"usedTokens\":%d,\"maxTokens\":%d}",
                    percentage, safeUsedTokens, safeMaxTokens);
            jsTarget.callJavaScript("onUsageUpdate", JsUtils.escapeJs(json));
            LOG.debug("Usage update sent to frontend: " + safeUsedTokens + "/" + safeMaxTokens);
        });
    }

    /**
     * Keep usage counters non-negative before they cross the JavaScript bridge.
     * Malformed or incomplete SDK usage payloads must not produce negative values
     * in the context tooltip.
     */
    static int normalizeUsageValue(int value) {
        return Math.max(0, value);
    }

    /**
     * Calculate a bounded usage percentage for the context indicator.
     */
    static double calculateUsagePercentage(int usedTokens, int maxTokens) {
        if (maxTokens <= 0) {
            return 0.0;
        }
        double percentage = usedTokens * 100.0 / maxTokens;
        return Math.max(0.0, Math.min(100.0, percentage));
    }

    @Override
    public void onUsageUpdate(String usageJson) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            jsTarget.callJavaScript("onUsageUpdate", JsUtils.escapeJs(usageJson));
            LOG.debug("Detailed usage update sent to frontend");
        });
    }

    @Override
    public void onUserMessageUuidPatched(String content, String uuid) {
        if (isInactive()) {
            return;
        }
        jsTarget.callJavaScript("patchMessageUuid", JsUtils.escapeJs(content), JsUtils.escapeJs(uuid));
    }

    @Override
    public void onQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState state, int aheadCount) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInactive()) {
                return;
            }
            String normalizedState = state != null ? state.name() : ClaudeSession.SessionCallback.QueueDisplayState.NONE.name();
            jsTarget.callJavaScript("showQueueStatus", JsUtils.escapeJs(normalizedState), String.valueOf(Math.max(0, aheadCount)));
        });
    }

    @Override
    public void onProtocolEvent(String type, String payloadJson) {
        if (isInactive()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!isInactive()) {
                jsTarget.dispatchEvent(type, payloadJson);
            }
        });
    }
    /**
     * Dispose internal resources. Call when the parent window is disposed.
     */
    public void dispose() {
        deactivate();
        try {
            streamEndFallbackAlarm.dispose();
        } catch (Exception e) {
            LOG.warn("Failed to dispose streamEndFallbackAlarm: " + e.getMessage());
        }
    }
}

