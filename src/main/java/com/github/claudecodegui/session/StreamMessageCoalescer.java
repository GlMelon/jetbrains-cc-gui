package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.MessageJsonConverter;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.Alarm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Coalesces streaming message updates to throttle webview pushes.
 * Batches rapid onMessageUpdate callbacks into periodic UI refreshes
 * to avoid overwhelming the JCEF browser.
 */
public class StreamMessageCoalescer implements Disposable {

    private static final Logger LOG = Logger.getInstance(StreamMessageCoalescer.class);
    private static final int UPDATE_INTERVAL_MS = 50;
    private static final int LARGE_UPDATE_PAYLOAD_CHARS = 150_000;
    private static final long SLOW_PAYLOAD_BUILD_MS = 25L;

    // FIX: Adaptive throttling to prevent JCEF IPC saturation during long streams.
    // When the full message JSON is large, V8 must parse the entire string literal
    // on every executeJavaScript call.  At 50ms intervals with 200KB+ payloads,
    // the renderer thread falls behind and enters a death spiral where IPC messages
    // pile up and ALL JavaScript calls (including onContentDelta) are blocked.
    //
    // Strategy: during active streaming, scale the coalescing interval based on the
    // last observed payload size.  Content updates still arrive via onContentDelta
    // (tiny payloads, <1KB), so the user sees streaming text.  Only the full message
    // list refresh (updateMessages) is throttled.
    private static final int LARGE_PAYLOAD_THRESHOLD = 100_000;   // 100KB
    private static final int MEDIUM_INTERVAL_MS = 500;             // 100-200KB
    private static final int LARGE_INTERVAL_MS = 2_000;            // 200-500KB
    private static final int XLARGE_INTERVAL_MS = 5_000;           // >500KB
    private static final int LONG_CONVERSATION_THRESHOLD = 300;
    private static final int LONG_CONVERSATION_TAIL_SIZE = 180;

    // During streaming, delta channel (onContentDelta/onThinkingDelta) provides
    // real-time character-by-character updates.  updateMessages carries authoritative
    // raw blocks (tool_use, tool_result, etc.) and is the ONLY channel that can
    // surface structural changes to the frontend.  Keep this minimum tight so that
    // newly-arrived tool_use / tool_result blocks show up promptly instead of
    // appearing to "stick" at the bottom while the user waits for an answer.
    // The adaptive thresholds above will still scale up for large payloads.
    private static final int STREAMING_MIN_INTERVAL_MS = 150;

    // FIX: Heartbeat interval during streaming.  During tool execution phases
    // (command execution, file operations, etc.), no content deltas or message
    // updates arrive from the SDK.  Without a heartbeat, the frontend stall
    // watchdog may falsely trigger and prematurely end the streaming state.
    // This lightweight signal keeps the frontend watchdog alive.
    private static final int HEARTBEAT_INTERVAL_MS = 10_000;       // 10s

    private final Object lock = new Object();
    private final Alarm updateAlarm;
    private final Alarm heartbeatAlarm;
    private volatile boolean streamActive = false;
    private volatile boolean disposed = false;
    private volatile boolean updateScheduled = false;
    private volatile long lastUpdateAtMs = 0L;
    private volatile long updateSequence = 0L;
    // Written from the pooled thread in sendToWebView, read from EDT/schedulePush via
    // effectiveIntervalMs().  Volatile guarantees visibility but not atomicity with the
    // lock-protected fields.  This is intentional: a one-cycle stale read only means the
    // interval adapts one push later — acceptable for a best-effort throttling heuristic.
    private volatile int lastPayloadChars = 0;
    private volatile List<ClaudeSession.Message> pendingMessages = null;
    private volatile List<ClaudeSession.Message> lastSnapshot = null;
    private volatile List<ClaudeSession.Message> lastDeliveredSnapshot = null;
    // usage 增量去重:流式期间重复推送相同 usage 的引用缓存(详见 sendToWebView 去重逻辑)。
    private volatile JsonObject lastPushedUsageRef = null;

    /**
     * ApplicationManager's pooled executor is not a bounded executor. Keep at
     * most one JSON serialization task in flight and retain only the newest
     * request, otherwise a slow JCEF/JSON path can queue a large number of
     * message snapshots and keep the whole conversation reachable.
     */
    private boolean serializationInFlight = false;
    private SerializationRequest pendingSerialization = null;
    /** The pooled task is kept behind a clearable holder so dispose() can release its snapshot. */
    private SerializationTask queuedSerializationTask;
    /** The EDT queue captures only this clearable holder, never the JSON string directly. */
    private DeliveryHolder queuedDelivery;

    private static final class SerializationTask {
        private final AtomicReference<SerializationRequest> request;

        private SerializationTask(SerializationRequest request) {
            this.request = new AtomicReference<>(request);
        }

        private SerializationRequest take() {
            return request.getAndSet(null);
        }

        private void clear() {
            request.set(null);
        }
    }

    private record DeliveryPayload(SerializationRequest request, String escapedMessagesJson) {
    }

    private static final class DeliveryHolder {
        private SerializationRequest request;
        private String escapedMessagesJson;
        private boolean cleared;

        private synchronized void set(SerializationRequest request, String escapedMessagesJson) {
            if (cleared) {
                return;
            }
            this.request = request;
            this.escapedMessagesJson = escapedMessagesJson;
        }

        private synchronized DeliveryPayload take() {
            if (cleared || request == null || escapedMessagesJson == null) {
                return null;
            }
            DeliveryPayload payload = new DeliveryPayload(request, escapedMessagesJson);
            request = null;
            escapedMessagesJson = null;
            return payload;
        }

        private synchronized void clear() {
            cleared = true;
            request = null;
            escapedMessagesJson = null;
        }
    }

    private final JsCallbackTarget callbackTarget;

    /**
     * Callback interface to push data to the webview.
     */
    public interface JsCallbackTarget {
        void callJavaScript(String functionName, String... args);
        JBCefBrowser getBrowser();
        boolean isDisposed();
        HandlerContext getHandlerContext();

        /**
         * Fired when the stream transitions to inactive (end of a turn's
         * streaming segment). Lets the host run work that was deferred while the
         * stream was active — e.g. a session_updated reload held back so it does
         * not disturb the streaming bubble or race SessionState mutations.
         * Default no-op so existing targets need not implement it.
         */
        default void onStreamEnded() {}
    }

    record MessageTransport(
            List<ClaudeSession.Message> messages,
            int baseIndex,
            boolean tailUpdate
    ) {}

    private record SerializationRequest(
            List<ClaudeSession.Message> originalMessages,
            List<ClaudeSession.Message> messages,
            long sequence,
            LongConsumer afterSendOnEdt,
            int tailBaseIndex,
            boolean tailUpdate,
            int originalMessageCount
    ) {}

    public StreamMessageCoalescer(JsCallbackTarget callbackTarget) {
        this(callbackTarget, null);
    }

    /**
     * @param parentDisposable optional parent that owns the coalescer's Alarms.
     *                         When provided, both Alarms are created with the
     *                         parent so they are released on project close even
     *                         if {@link #dispose()} is never invoked — a backstop
     *                         against the Alarm-without-disposable leak. Pass
     *                         {@code null} for lightweight usage (e.g. tests).
     */
    public StreamMessageCoalescer(JsCallbackTarget callbackTarget, Disposable parentDisposable) {
        this.callbackTarget = callbackTarget;
        if (parentDisposable != null) {
            this.updateAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable);
            this.heartbeatAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable);
        } else {
            this.updateAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
            this.heartbeatAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
        }
    }

    /**
     * Enqueue a message update for coalesced delivery.
     */
    public void enqueue(List<ClaudeSession.Message> messages) {
        if (messages == null || disposed || callbackTarget.isDisposed()) {
            return;
        }
        // Defensive copy: the caller's list may be mutated on another thread,
        // so we snapshot it here to guarantee a consistent read in sendToWebView.
        final List<ClaudeSession.Message> snapshot = List.copyOf(messages);
        synchronized (lock) {
            if (disposed) {
                return;
            }
            pendingMessages = snapshot;
        }
        schedulePush();
        // Restart heartbeat timer: real data just arrived, so the next heartbeat
        // should fire HEARTBEAT_INTERVAL_MS from now, not from the last heartbeat.
        if (streamActive) {
            startHeartbeat();
        }
    }

    /**
     * Notify that a stream has started.
     */
    public void onStreamStart() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            streamActive = true;
        }
        startHeartbeat();
    }

    /**
     * Notify that a stream has ended.
     */
    public void onStreamEnd() {
        if (disposed) {
            return;
        }
        heartbeatAlarm.cancelAllRequests();
        synchronized (lock) {
            if (disposed) {
                return;
            }
            streamActive = false;
            lastPayloadChars = 0;  // Reset so post-stream flush uses normal interval
        }
        // Notify the host that the stream went inactive, so it can drain work
        // deferred during streaming (e.g. a background session_updated reload).
        // Done outside the lock: the host may synchronously schedule EDT work,
        // and holding `lock` across a foreign callback risks lock-ordering issues.
        try {
            callbackTarget.onStreamEnded();
        } catch (Exception e) {
            LOG.warn("Failed to notify stream end: " + e.getMessage(), e);
        }
    }

    /**
     * Reset stream state (e.g., on new session creation).
     */
    public void resetStreamState() {
        updateAlarm.cancelAllRequests();
        heartbeatAlarm.cancelAllRequests();
        synchronized (lock) {
            if (disposed) {
                return;
            }
            streamActive = false;
            updateScheduled = false;
            pendingMessages = null;
            lastSnapshot = null;
            lastDeliveredSnapshot = null;
            lastUpdateAtMs = 0L;
            lastPayloadChars = 0;
            ++updateSequence;
            lastPushedUsageRef = null;  // 新会话:旧 usage 引用缓存失效
        }
    }

    public boolean isStreamActive() {
        return streamActive;
    }

    /**
     * Flush any pending messages immediately and optionally run a callback afterwards.
     */
    public void flush(LongConsumer afterFlushOnEdt) {
        if (disposed || callbackTarget.isDisposed()) {
            return;
        }

        final List<ClaudeSession.Message> snapshot;
        final long sequence;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            updateAlarm.cancelAllRequests();
            updateScheduled = false;
            snapshot = pendingMessages != null ? pendingMessages : lastSnapshot;
            pendingMessages = null;
            sequence = ++updateSequence;
        }

        if (snapshot == null) {
            if (afterFlushOnEdt != null) {
                final long finalSequence = sequence;
                scheduleOnEdt(() -> afterFlushOnEdt.accept(finalSequence), null);
            }
            return;
        }

        sendToWebView(snapshot, sequence, afterFlushOnEdt);
    }

    /**
     * Dispose internal resources.
     */
    @Override
    public void dispose() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
            streamActive = false;
            updateScheduled = false;
            pendingMessages = null;
            lastSnapshot = null;
            lastDeliveredSnapshot = null;
            lastPushedUsageRef = null;
            lastPayloadChars = 0;
            ++updateSequence;
            pendingSerialization = null;
            serializationInFlight = false;
            SerializationTask serializationTask = queuedSerializationTask;
            queuedSerializationTask = null;
            DeliveryHolder deliveryHolder = queuedDelivery;
            queuedDelivery = null;
            if (serializationTask != null) {
                serializationTask.clear();
            }
            if (deliveryHolder != null) {
                deliveryHolder.clear();
            }
        }
        try {
            updateAlarm.cancelAllRequests();
            updateAlarm.dispose();
        } catch (Exception e) {
            LOG.warn("Failed to dispose stream message update alarm: " + e.getMessage());
        }
        try {
            heartbeatAlarm.cancelAllRequests();
            heartbeatAlarm.dispose();
        } catch (Exception e) {
            LOG.warn("Failed to dispose heartbeat alarm: " + e.getMessage());
        }
    }

    /**
     * Compute the effective coalescing interval.  During streaming, scale the
     * interval based on the last observed payload size to prevent JCEF overload.
     */
    private int effectiveIntervalMs() {
        if (!streamActive) {
            return UPDATE_INTERVAL_MS;
        }
        int chars = lastPayloadChars;
        int interval;
        if (chars > 500_000) {
            interval = XLARGE_INTERVAL_MS;
        } else if (chars > 200_000) {
            interval = LARGE_INTERVAL_MS;
        } else if (chars > LARGE_PAYLOAD_THRESHOLD) {
            interval = MEDIUM_INTERVAL_MS;
        } else {
            return STREAMING_MIN_INTERVAL_MS;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AdaptiveThrottle] payload=" + chars + " chars → interval=" + interval + "ms");
        }
        return interval;
    }

    private void schedulePush() {
        if (disposed || callbackTarget.isDisposed()) {
            return;
        }

        final int delayMs;
        synchronized (lock) {
            if (updateScheduled) {
                return;
            }
            int intervalMs = effectiveIntervalMs();
            long elapsed = System.currentTimeMillis() - lastUpdateAtMs;
            delayMs = (int) Math.max(0L, intervalMs - elapsed);
            updateScheduled = true;
            ++updateSequence;
        }

        updateAlarm.addRequest(() -> {
            final List<ClaudeSession.Message> snapshot;
            final long sequence;
            synchronized (lock) {
                updateScheduled = false;
                lastUpdateAtMs = System.currentTimeMillis();
                snapshot = pendingMessages;
                pendingMessages = null;
                sequence = updateSequence;
            }

            if (disposed || callbackTarget.isDisposed()) {
                return;
            }

            if (snapshot != null) {
                sendToWebView(snapshot, sequence, null);
            }

            boolean hasPending;
            synchronized (lock) {
                hasPending = pendingMessages != null;
            }
            if (hasPending && !disposed && !callbackTarget.isDisposed()) {
                schedulePush();
            }
        }, delayMs);
    }

    private void sendToWebView(
            List<ClaudeSession.Message> messages,
            long sequence,
            LongConsumer afterSendOnEdt
    ) {
        // Keep the snapshot for potential re-flush after webview reload/recreate.
        // Only a snapshot actually delivered to the webview can prove that an
        // omitted prefix is stable enough for an indexed tail update.
        final SerializationRequest request;
        SerializationRequest requestToStart = null;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            MessageTransport transport = selectMessageTransport(messages, lastDeliveredSnapshot);
            lastSnapshot = messages;
            request = new SerializationRequest(
                    messages,
                    transport.messages(),
                    sequence,
                    afterSendOnEdt,
                    transport.baseIndex(),
                    transport.tailUpdate(),
                    messages.size()
            );
            if (serializationInFlight) {
                if (pendingSerialization != null) {
                    SerializationRequest previous = pendingSerialization;
                    LongConsumer mergedCallback = mergeCallbacks(previous, request);
                    pendingSerialization = new SerializationRequest(
                            request.originalMessages(),
                            request.messages(),
                            request.sequence(),
                            mergedCallback,
                            request.tailBaseIndex(),
                            request.tailUpdate(),
                            request.originalMessageCount()
                    );
                } else {
                    pendingSerialization = request;
                }
            } else {
                serializationInFlight = true;
                requestToStart = request;
            }
        }
        if (requestToStart != null) {
            startSerialization(requestToStart);
        }
    }

    private static LongConsumer mergeCallbacks(
            SerializationRequest previous,
            SerializationRequest newest
    ) {
        LongConsumer previousCallback = previous.afterSendOnEdt();
        LongConsumer newestCallback = newest.afterSendOnEdt();
        if (previousCallback == null) {
            return newestCallback;
        }
        if (newestCallback == null) {
            return sequence -> previousCallback.accept(previous.sequence());
        }
        return sequence -> {
            previousCallback.accept(previous.sequence());
            newestCallback.accept(sequence);
        };
    }

    private void startSerialization(SerializationRequest request) {
        if (disposed) {
            finishSerialization();
            return;
        }
        SerializationTask task = new SerializationTask(request);
        synchronized (lock) {
            if (disposed) {
                task.clear();
                return;
            }
            queuedSerializationTask = task;
        }
        try {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                synchronized (lock) {
                    if (queuedSerializationTask == task) {
                        queuedSerializationTask = null;
                    }
                }
                SerializationRequest taskRequest = task.take();
                if (taskRequest == null) {
                    finishSerialization();
                    return;
                }
                serializeAndSchedule(taskRequest);
            });
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (queuedSerializationTask == task) {
                    queuedSerializationTask = null;
                }
            }
            SerializationRequest failedRequest = task.take();
            LOG.warn("Failed to schedule message serialization: " + e.getMessage(), e);
            if (failedRequest != null) {
                runAfterSend(failedRequest);
            }
            finishSerialization();
        }
    }

    private void serializeAndSchedule(SerializationRequest request) {
        if (disposed) {
            finishSerialization();
            return;
        }
        final int payloadChars;
        final long payloadBuildMs;
        final String escapedMessagesJson;
        try {
            long buildStartedAt = System.nanoTime();
            String messagesJson = MessageJsonConverter.convertMessagesToJson(request.messages());
            payloadChars = messagesJson.length();
            escapedMessagesJson = JsUtils.escapeJs(messagesJson);
            payloadBuildMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - buildStartedAt
            );

            lastPayloadChars = payloadChars;
            if (payloadChars >= LARGE_UPDATE_PAYLOAD_CHARS || payloadBuildMs >= SLOW_PAYLOAD_BUILD_MS) {
                LOG.info("[WebviewTransport] updateMessages payload chars=" + payloadChars
                        + ", messages=" + request.originalMessageCount()
                        + ", transportedMessages=" + request.messages().size()
                        + ", tailBaseIndex=" + request.tailBaseIndex()
                        + ", buildMs=" + payloadBuildMs
                        + ", sequence=" + request.sequence());
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("[WebviewTransport] updateMessages payload chars=" + payloadChars
                        + ", messages=" + request.originalMessageCount()
                        + ", transportedMessages=" + request.messages().size()
                        + ", buildMs=" + payloadBuildMs
                        + ", sequence=" + request.sequence());
            }
        } catch (Exception e) {
            LOG.warn("Failed to serialize messages for streaming update: " + e.getMessage(), e);
            runAfterSend(request);
            finishSerialization();
            return;
        }

        if (disposed) {
            finishSerialization();
            return;
        }

        DeliveryHolder deliveryHolder = new DeliveryHolder();
        deliveryHolder.set(request, escapedMessagesJson);
        synchronized (lock) {
            if (disposed) {
                deliveryHolder.clear();
                finishSerialization();
                return;
            }
            queuedDelivery = deliveryHolder;
        }
        Runnable delivery = () -> deliverOnEdt(deliveryHolder);

        if (!scheduleOnEdt(delivery, request)) {
            synchronized (lock) {
                if (queuedDelivery == deliveryHolder) {
                    queuedDelivery = null;
                }
            }
            deliveryHolder.clear();
            runAfterSend(request);
            finishSerialization();
        }
    }

    private void deliverOnEdt(DeliveryHolder deliveryHolder) {
        synchronized (lock) {
            if (queuedDelivery == deliveryHolder) {
                queuedDelivery = null;
            }
        }
        DeliveryPayload payload = deliveryHolder.take();
        if (payload == null) {
            finishSerialization();
            return;
        }
        SerializationRequest request = payload.request();
        String escapedMessagesJson = payload.escapedMessagesJson();
        try {
            if (disposed || callbackTarget.isDisposed()) {
                return;
            }

            synchronized (lock) {
                if (disposed || request.sequence() != updateSequence) {
                    return;
                }
            }

            try {
                if (request.tailUpdate()) {
                    callbackTarget.callJavaScript(
                            "updateMessageTail",
                            escapedMessagesJson,
                            String.valueOf(request.tailBaseIndex()),
                            String.valueOf(request.sequence()));
                } else {
                    callbackTarget.callJavaScript(
                            "updateMessages",
                            escapedMessagesJson,
                            String.valueOf(request.sequence())
                    );
                }
                synchronized (lock) {
                    if (!disposed && request.sequence() == updateSequence) {
                        lastDeliveredSnapshot = request.originalMessages();
                    }
                }
                JsonObject currentUsage = TokenUsageUtils.findLastUsageFromSessionMessages(request.originalMessages());
                if (currentUsage != lastPushedUsageRef) {
                    lastPushedUsageRef = currentUsage;
                    MessageJsonConverter.pushUsageUpdateFromMessages(
                            request.originalMessages(),
                            callbackTarget.getHandlerContext(),
                            callbackTarget.getBrowser(),
                            callbackTarget.isDisposed()
                    );
                }
            } catch (Exception e) {
                LOG.warn("Failed to push updateMessages to webview (payload chars="
                        + escapedMessagesJson.length() + "): " + e.getMessage(), e);
            }
        } finally {
            runAfterSend(request);
            finishSerialization();
        }
    }

    private boolean scheduleOnEdt(Runnable runnable, SerializationRequest request) {
        if (disposed) {
            return false;
        }
        try {
            ApplicationManager.getApplication().invokeLater(runnable);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Failed to schedule webview callback on EDT"
                    + (request != null ? " (sequence=" + request.sequence() + ")" : "")
                    + ": " + e.getMessage(), e);
            return false;
        }
    }
    private void runAfterSend(SerializationRequest request) {
        if (request.afterSendOnEdt() == null) {
            return;
        }
        try {
            request.afterSendOnEdt().accept(request.sequence());
        } catch (Exception e) {
            LOG.warn("Failed to run after-send callback: " + e.getMessage(), e);
        }
    }

    private void finishSerialization() {
        SerializationRequest next;
        synchronized (lock) {
            if (disposed) {
                pendingSerialization = null;
                serializationInFlight = false;
                return;
            }
            next = pendingSerialization;
            pendingSerialization = null;
            if (next == null) {
                serializationInFlight = false;
                return;
            }
        }
        startSerialization(next);
    }
    static MessageTransport selectMessageTransport(
            List<ClaudeSession.Message> messages,
            List<ClaudeSession.Message> previousMessages
    ) {
        boolean longConversation = messages.size() > LONG_CONVERSATION_THRESHOLD;
        int candidateBaseIndex = longConversation
                ? Math.max(0, messages.size() - LONG_CONVERSATION_TAIL_SIZE)
                : 0;
        boolean stablePrefix = previousMessages != null
                && messages.size() >= previousMessages.size()
                && hasSamePrefix(previousMessages, messages, candidateBaseIndex);
        boolean tailUpdate = longConversation && stablePrefix;
        int baseIndex = tailUpdate ? candidateBaseIndex : 0;
        List<ClaudeSession.Message> transportMessages = tailUpdate
                ? List.copyOf(messages.subList(baseIndex, messages.size()))
                : messages;
        return new MessageTransport(transportMessages, baseIndex, tailUpdate);
    }

    private static boolean hasSamePrefix(
            List<ClaudeSession.Message> previousMessages,
            List<ClaudeSession.Message> messages,
            int prefixLength
    ) {
        if (previousMessages.size() < prefixLength) {
            return false;
        }
        for (int i = 0; i < prefixLength; i++) {
            if (previousMessages.get(i) != messages.get(i)) {
                return false;
            }
        }
        return true;
    }

    // ===== Streaming heartbeat =====

    /**
     * Start (or restart) the periodic heartbeat during streaming.
     * Sends a lightweight JS signal to the frontend to prevent the stall
     * watchdog from falsely triggering during tool execution phases where
     * no content deltas or message updates arrive from the SDK.
     */
    private void startHeartbeat() {
        heartbeatAlarm.cancelAllRequests();
        scheduleHeartbeat();
    }

    private void scheduleHeartbeat() {
        if (disposed || !streamActive || callbackTarget.isDisposed()) {
            return;
        }
        heartbeatAlarm.addRequest(() -> {
            if (disposed || !streamActive || callbackTarget.isDisposed()) {
                return;
            }
            try {
                callbackTarget.callJavaScript("onStreamingHeartbeat");
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[Heartbeat] Sent streaming heartbeat to frontend");
                }
            } catch (Exception e) {
                LOG.warn("[Heartbeat] Failed to send heartbeat: " + e.getMessage());
            }
            // Schedule next heartbeat
            scheduleHeartbeat();
        }, HEARTBEAT_INTERVAL_MS);
    }
}
