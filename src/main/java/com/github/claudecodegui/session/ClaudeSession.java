package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.provider.claude.ClaudeHistoryService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Session management for Claude conversations.
 * Maintains state and message history for a single chat session.
 */
public class ClaudeSession {

    private static final Logger LOG = Logger.getInstance(ClaudeSession.class);

    /**
     * Maximum file size for Codex context injection (100KB)
     */
    private static final int MAX_FILE_SIZE_BYTES = 100 * 1024;

    private final Gson gson = GsonHolder.GSON;

    /**
     * Flag set when the user manually interrupts the current turn (clicks Stop).
     * Checked by {@link com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow#onStreamEnded()}
     * to suppress the task-completion notification sound for manual stops.
     * Reset to {@code false} at the start of each new {@link #send} call.
     */
    private volatile boolean manuallyInterrupted = false;

    // Session state manager
    private final com.github.claudecodegui.session.SessionState state;

    // Message processors
    private final com.github.claudecodegui.session.MessageParser messageParser;
    private final com.github.claudecodegui.session.MessageMerger messageMerger;

    // Context collector
    private final com.github.claudecodegui.session.EditorContextCollector contextCollector;
    private final SessionContextService contextService;
    private final SessionProviderRouter providerRouter;
    private final SessionSendService sendService;
    private final SessionMessageOrchestrator messageOrchestrator;

    // Callback facade
    private final SessionCallbackFacade callbackFacade;

    // Track when the last turn was started
    /** Start time of the latest submitted turn, retained across Webview rebuilds. */
    private volatile long lastTurnStartedAtMillis;

    /**
     * Represents a single message in the conversation.
     */
    public static class Message {
        public enum Type {
            USER(CommonConstants.MSG_TYPE_USER),
            ASSISTANT(CommonConstants.MSG_TYPE_ASSISTANT),
            SYSTEM(CommonConstants.MSG_TYPE_SYSTEM),
            ERROR(CommonConstants.MSG_TYPE_ERROR);

            private final String value;

            Type(String value) {
                this.value = value;
            }

            /** 该类型的序列化值(wire format),与 {@link CommonConstants#MSG_TYPE_USER} 等对齐。 */
            public String value() {
                return value;
            }

            /** 从序列化值反查类型;未知值或 null 返回 null。 */
            public static Type fromValue(String value) {
                if (value == null) {
                    return null;
                }
                for (Type type : values()) {
                    if (type.value.equals(value)) {
                        return type;
                    }
                }
                return null;
            }
        }

        public Type type;
        // The streaming handler thread reassigns these on every assistant update
        // (e.g. `raw = mergedRaw`, `content = builder.toString()`) while
        // StreamMessageCoalescer serializes the same Message off-EDT — enqueue only
        // shallow-copies the list, so elements are shared across threads. Without
        // volatile the serializer could read a stale reference and publish a snapshot
        // predating a just-reassigned tool_use block, which the frontend's structural
        // merge (it takes blocks from the new snapshot only) would then freeze as
        // missing.
        // This covers the reassignment race, the dominant mutation pattern. Note:
        // a few call sites still mutate the JsonObject in place (turnUsage / uuid /
        // usage stamps in ClaudeMessageHandler); those are a separate concern.
        public volatile String content;
        public long timestamp;
        public volatile JsonObject raw; // Raw message data from the CLI stream

        public Message(Type type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public Message(Type type, String content, JsonObject raw) {
            this(type, content);
            this.raw = raw;
        }
    }

    /**
     * Callback interface for session events.
     */
    public interface SessionCallback {
        enum QueueDisplayState {
            NONE,
            QUEUED,
            PROCESSING,
            COMPLETED
        }

        void onMessageUpdate(List<Message> messages);

        void onStateChange(boolean busy, boolean loading, String error);

        default void onStatusMessage(String message) {
        }

        void onSessionIdReceived(String sessionId);

        void onThinkingStatusChanged(boolean isThinking);

        void onSlashCommandsReceived(List<String> slashCommands);

        void onNodeLog(String log);

        void onSummaryReceived(String summary);

        // Streaming callback methods (with default implementations for backward compatibility)
        default void onStreamStart() {
        }

        default void onResponsePhase(AssistantResponseStatusPayload payload) {
        }

        default void onStreamEnd() {
        }

        default void onStreamCompleted() {
        }

        default void onContentDelta(String delta) {
        }

        default void onThinkingDelta(String delta) {
        }

        /**
         * Called when a block reset signal is received during streaming.
         * This indicates a new assistant message has started within the stream
         * (e.g., after a tool_use loop iteration), and the frontend should
         * clear its streaming content refs to prevent cross-turn content merging.
         */
        default void onBlockReset() {
        }

        default void onUsageUpdate(int usedTokens, int maxTokens) {
        }

        default void onUsageUpdate(String usageJson) {
        }

        default void onUserMessageUuidPatched(String content, String uuid) {
        }

        /**
         * Variant carrying rewind availability so CLI-mode turns (no history reload)
         * can mark the just-sent user message rewindable; defaults to non-rewindable.
         */
        default void onUserMessageUuidPatched(String content, String uuid, boolean rewindable) {
            onUserMessageUuidPatched(content, uuid);
        }

        default void onQueueDisplayStateChanged(QueueDisplayState state, int aheadCount) {
        }

        default void onProtocolEvent(String type, String payloadJson) {
        }
    }

    public ClaudeSession(Project project) {
        this.state = new com.github.claudecodegui.session.SessionState();
        this.messageParser = new com.github.claudecodegui.session.MessageParser();
        this.messageMerger = new com.github.claudecodegui.session.MessageMerger();
        this.contextCollector = new com.github.claudecodegui.session.EditorContextCollector(project);
        this.callbackFacade = new SessionCallbackFacade(project);
        this.contextService = new SessionContextService(project, MAX_FILE_SIZE_BYTES);
        this.providerRouter = new SessionProviderRouter();
        this.sendService = new SessionSendService(
                project,
                state,
                callbackFacade,
                messageParser,
                messageMerger,
                gson,
                contextService
        );
        ClaudeHistoryService claudeHistoryService = new ClaudeHistoryService();
        this.messageOrchestrator = new SessionMessageOrchestrator(
                project,
                state,
                messageParser,
                callbackFacade,
                new SessionMessageOrchestrator.SessionHistoryAccess() {
                    @Override
                    public List<JsonObject> getProviderSessionMessages(String provider, String sessionId, String cwd) {
                        return providerRouter.getSessionMessages(provider, sessionId, cwd);
                    }

                    @Override
                    public com.github.claudecodegui.provider.SessionHistoryLoadResult getProviderInitialSessionHistory(
                            String provider, String sessionId, String cwd) {
                        return providerRouter.getInitialSessionHistory(provider, sessionId, cwd);
                    }
                    @Override
                    public JsonObject getLatestClaudeUserMessage(String sessionId, String cwd) {
                        return claudeHistoryService.getLatestUserMessage(sessionId, cwd);
                    }
                }
        );
    }

    public void setCallback(SessionCallback callback) {
        callbackFacade.setCallback(callback);
    }

    public com.github.claudecodegui.session.EditorContextCollector getContextCollector() {
        return contextCollector;
    }

    // Getters - delegated to SessionState
    public String getSessionId() {
        return state.getSessionId();
    }

    public String getChannelId() {
        return state.getChannelId();
    }

    /** Returns the negotiated capabilities of the concrete runtime session. */
    public SessionNegotiatedCapabilities getSessionCapabilities() {
        return sendService.getSessionCapabilities(state.getChannelId(), state.getProvider());
    }

    public boolean isLoading() {
        return state.isLoading();
    }

    public String getError() {
        return state.getError();
    }

    /**
     * Returns whether the current (or most recent) turn was manually interrupted
     * by the user clicking Stop. Used to suppress the task-completion sound.
     *
     * @return {@code true} if the user manually interrupted the current turn
     */
    public boolean isManuallyInterrupted() {
        return manuallyInterrupted;
    }

    public List<Message> getMessages() {
        return state.getMessages();
    }

    /**
     * 提供底层会话状态访问，用于历史恢复等需要直接重建会话内存态的场景。
     */
    public SessionState getState() {
        return state;
    }

    public SessionSkillSnapshot getSkillSnapshot() {
        return state.getSkillSnapshot();
    }

    public void setSkillSnapshot(SessionSkillSnapshot snapshot) {
        state.setSkillSnapshot(snapshot);
    }

    public String getSummary() {
        return state.getSummary();
    }

    public long getLastModifiedTime() {
        return state.getLastModifiedTime();
    }

    /**
     * Set session ID and working directory (used for session restoration).
     */
    public void setSessionInfo(String sessionId, String cwd) {
        state.setSessionId(sessionId);
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            callbackFacade.notifySessionIdReceived(sessionId);
        }
        if (cwd != null) {
            setCwd(cwd);
        } else {
            state.setCwd(null);
        }
    }

    /**
     * Get the current working directory.
     */
    public String getCwd() {
        return state.getCwd();
    }

    /**
     * Set the working directory.
     */
    public void setCwd(String cwd) {
        state.setCwd(cwd);
        LOG.info("Working directory updated to: " + cwd);
    }

    /**
     * Launch Claude agent.
     * Reuses existing channelId if available, otherwise creates a new one.
     */
    public CompletableFuture<String> launchClaude() {
        if (state.getChannelId() != null) {
            return CompletableFuture.completedFuture(state.getChannelId());
        }

        state.setError(null);
        state.setChannelId(UUID.randomUUID().toString());

        // CLI 模式:会话在首次 send 时由 CliSessionManager 启动,直接返回 channelId。
        return CompletableFuture.completedFuture(state.getChannelId());
    }

    /**
     * Send a message with a specific agent prompt.
     * Used for per-tab independent agent selection.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt) {
        return send(input, null, agentPrompt, null, null);
    }

    /**
     * Send a message with a specific agent prompt and file tags.
     * Used for Codex context injection.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt, List<String> fileTagPaths) {
        return send(input, null, agentPrompt, fileTagPaths, null);
    }

    /**
     * Send a message with a specific agent prompt, file tags and requested permission mode.
     * requestedPermissionMode priority: payload > sessionMode > default.
     */
    public CompletableFuture<Void> send(String input, String agentPrompt, List<String> fileTagPaths, String requestedPermissionMode) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode);
    }

    /**
     * Send a message with a specific agent prompt, file tags, requested permission mode,
     * and an optional DSH agent preset (per-message preset switching).
     */
    public CompletableFuture<Void> send(
            String input,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedDshPreset
    ) {
        return send(input, null, agentPrompt, fileTagPaths, requestedPermissionMode, requestedDshPreset);
    }

    /**
     * Send a message with attachments and a specific agent prompt.
     * Used for per-tab independent agent selection.
     *
     * @param input       User input text
     * @param attachments List of attachments (nullable)
     * @param agentPrompt Agent prompt (falls back to global setting if null)
     */
    public CompletableFuture<Void> send(String input, List<Attachment> attachments, String agentPrompt) {
        return send(input, attachments, agentPrompt, null, null);
    }

    /**
     * Send a message with attachments, agent prompt, and file tags.
     * Used for Codex context injection.
     *
     * @param input        User input text
     * @param attachments  List of attachments (nullable)
     * @param agentPrompt  Agent prompt (falls back to global setting if null)
     * @param fileTagPaths File tag paths for Codex context injection
     */
    public CompletableFuture<Void> send(String input, List<Attachment> attachments, String agentPrompt, List<String> fileTagPaths) {
        return send(input, attachments, agentPrompt, fileTagPaths, null);
    }

    public CompletableFuture<Void> send(
            String input,
            List<Attachment> attachments,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
    ) {
        return send(input, attachments, agentPrompt, fileTagPaths, requestedPermissionMode, null);
    }

    /**
     * Send a message with attachments and an optional DSH agent preset.
     */
    public CompletableFuture<Void> send(
            String input,
            List<Attachment> attachments,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedDshPreset
    ) {
        LOG.debug("[ClaudeSession][DIAG] send() called, attachments="
                + (attachments == null ? "NULL" : attachments.size()));
        if (attachments != null) {
            for (int i = 0; i < attachments.size(); i++) {
                Attachment att = attachments.get(i);
                LOG.debug("[ClaudeSession][DIAG] att[" + i + "]: fileName=" + att.fileName
                        + ", localPath=" + att.localPath
                        + ", data=" + (att.data != null ? att.data.length() + "chars" : "null")
                        + ", resourceUrl=" + att.resourceUrl);
            }
        }
        lastTurnStartedAtMillis = System.currentTimeMillis();
        // Reset the manual-interrupt flag at the start of a new turn so that
        // a fresh send is not mistaken for a user-initiated stop.
        manuallyInterrupted = false;
        String normalizedInput = (input != null) ? input.trim() : "";
        Message userMessage = contextService.buildUserMessage(normalizedInput, attachments);
        sendService.updateSessionStateForSend(userMessage, normalizedInput);
        final long sendInvalidationEpoch = state.capturePendingSendInvalidationEpoch();

        final String finalAgentPrompt = agentPrompt;
        final List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;
        final String finalRequestedDshPreset = requestedDshPreset;

        return launchClaude().thenCompose(chId -> {
            if (!state.isPendingSendOperationCurrent(sendInvalidationEpoch)) {
                return CompletableFuture.completedFuture(null);
            }
            sendService.prepareContextCollector(contextCollector);

            return contextCollector.collectContext().thenCompose(openedFilesJson -> {
                if (!state.isPendingSendOperationCurrent(sendInvalidationEpoch)) {
                    return CompletableFuture.completedFuture(null);
                }
                return sendService.sendMessageToProvider(
                        chId,
                        userMessage.content,
                        attachments,
                        openedFilesJson,
                        finalAgentPrompt,
                        finalFileTagPaths,
                        finalRequestedPermissionMode,
                        finalRequestedDshPreset
                );
            }).thenCompose(v -> {
                if (!state.isPendingSendOperationCurrent(sendInvalidationEpoch)) {
                    return CompletableFuture.completedFuture(null);
                }
                return syncUserMessageUuidsAfterSend();
            });
        }).exceptionally(ex -> {
            state.setError(ex.getMessage());
            state.setBusy(false);
            state.setLoading(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return null;
        });
    }

    private CompletableFuture<Void> syncUserMessageUuidsAfterSend() {
        return messageOrchestrator.syncUserMessageUuidsAfterSend();
    }

    /**
     * Interrupt the current execution.
     */
    public CompletableFuture<Void> interrupt() {
        state.invalidatePendingSendOperations();

        // Mark this turn as manually interrupted so the stream-end handler
        // suppresses the task-completion notification sound.
        manuallyInterrupted = true;

        // 发送用户取消状态给前端，避免卡片空白
        String provider = state.getProvider();
        callbackFacade.notifyResponsePhase(AssistantResponseStatusPayload.forCancelled(provider));
        
        if (state.getChannelId() == null) {
            state.setError(null);
            state.setBusy(false);
            state.setLoading(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return CompletableFuture.completedFuture(null);
        }

        // 显式 executor:interruptRuntime 走 ProcessManager 终止链(Windows taskkill /T + waitFor
        // 3s + 2s),无 executor 会把 commonPool worker 占住数秒。
        return CompletableFuture.runAsync(() -> {
            try {
                sendService.interruptRuntime(state.getProvider(), state.getChannelId(), state.getChannelId());
                state.setError(null);  // Clear previous error state
                state.setBusy(false);
                state.setLoading(false);  // Also reset loading state

                // Note: We intentionally don't call notifyStreamEnd() here because:
                // 1. The frontend's interruptSession() already cleans up streaming state directly
                // 2. Calling notifyStreamEnd() would trigger flushStreamMessageUpdates(),
                //    which might restore previous messages via lastMessagesSnapshot, interfering with clearMessages
                // 3. State reset is notified via callbackFacade.notifyStateChange()

                callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            } catch (Exception e) {
                state.setError(e.getMessage());
                state.setLoading(false);  // Also reset loading on error
                callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Restart the Claude agent.
     */
    public CompletableFuture<Void> restart() {
        return interrupt().thenCompose(v -> {
            state.setChannelId(null);
            state.setBusy(false);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            return launchClaude().thenApply(chId -> null);
        });
    }

    /**
     * Load message history from the server.
     */
    public CompletableFuture<Void> loadFromServer() {
        return messageOrchestrator.loadFromServer();
    }

    public void applyCodexHistoryPage(
            List<JsonObject> serverMessages,
            CodexHistoryPageMode mode
    ) {
        List<Message> parsedMessages = new ArrayList<>();
        if (serverMessages != null) {
            for (JsonObject serverMessage : serverMessages) {
                Message parsed = messageParser.parseServerMessage(serverMessage);
                if (parsed != null) {
                    parsedMessages.add(parsed);
                }
            }
        }
        if (mode == CodexHistoryPageMode.PREPEND) {
            state.prependMessages(parsedMessages);
        } else {
            state.replaceMessages(parsedMessages);
        }
        callbackFacade.notifyMessageUpdate(state.getMessages());
    }
    /**
     * Represents a file attachment (e.g., image).
     */
    public static class Attachment {
        public String fileName;
        public String mediaType;
        public String data; // Base64 encoded data
        public String localPath;
        public String resourceUrl;
        public String thumbnailUrl;
        public String attachmentHash;

        public Attachment(String fileName, String mediaType, String data) {
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.data = data;
        }

        public Attachment(
                String fileName,
                String mediaType,
                String data,
                String localPath,
                String resourceUrl,
                String thumbnailUrl,
                String attachmentHash
        ) {
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.data = data;
            this.localPath = localPath;
            this.resourceUrl = resourceUrl;
            this.thumbnailUrl = thumbnailUrl;
            this.attachmentHash = attachmentHash;
        }
    }

    /**
     * Dispose this session, releasing all held resources and breaking reference chains.
     * Must be called when the associated tab/window is closed to prevent memory leaks.
     */
    public void dispose() {
        String tabId = state.getChannelId();
        LOG.info("[ClaudeSession] Disposing session, channelId=" + tabId);

        // Interrupt any active request
        try {
            interrupt();
        } catch (Exception e) {
            LOG.debug("[ClaudeSession] Interrupt during dispose failed: " + e.getMessage());
        }

        // 释放 runtime、标题任务以及 CLI manager，避免 tab/window 关闭后异步任务继续持有 UI 引用。
        try {
            sendService.dispose();
        } catch (Exception e) {
            LOG.warn("[ClaudeSession] send service dispose failed: " + e.getMessage());
        }

        // Clear callback reference to break: callbackFacade -> UI
        callbackFacade.setCallback(null);

        state.setChannelId(null);
    }

    /**
     * Set the permission mode.
     */
    public void setPermissionMode(String mode) {
        state.setPermissionMode(mode);
    }

    /**
     * Get the permission mode.
     */
    public String getPermissionMode() {
        return state.getPermissionMode();
    }

    /**
     * Set the model.
     */
    public void setModel(String model) {
        state.setModel(model);
        LOG.info("Model updated to: " + model);
    }

    /**
     * Get the model.
     */
    public String getModel() {
        return state.getModel();
    }

    /**
     * Returns the start time of the latest submitted turn, or {@code 0} when
     * no turn has been submitted yet.
     */
    public long getLastTurnStartedAtMillis() {
        return lastTurnStartedAtMillis;
    }

    /**
     * Set the AI provider.
     */
    public void setProvider(String provider) {
        state.setProvider(provider);
        LOG.info("Provider updated to: " + provider);
    }

    /**
     * Get the AI provider.
     */
    public String getProvider() {
        return state.getProvider();
    }

    public void setPermissionSessionId(String permissionSessionId) {
        state.setPermissionSessionId(permissionSessionId);
    }

    /**
     * Get the current runtime session epoch.
     */
    public String getRuntimeSessionEpoch() {
        return state.getRuntimeSessionEpoch();
    }

    /**
     * Set the reasoning effort level.
     */
    public void setReasoningEffort(String effort) {
        state.setReasoningEffort(effort);
        LOG.info("Reasoning effort updated to: " + effort);
    }

    /**
     * Get the reasoning effort level.
     */
    public String getReasoningEffort() {
        return state.getReasoningEffort();
    }

    /**
     * Set the Codex service tier.
     */
    public void setCodexServiceTier(String serviceTier) {
        state.setCodexServiceTier(serviceTier);
    }
}
