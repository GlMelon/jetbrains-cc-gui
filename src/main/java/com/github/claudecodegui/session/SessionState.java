package com.github.claudecodegui.session;


import com.github.claudecodegui.common.CommonConstants;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session state management.
 * Maintains all state information for a conversation session.
 */
public class SessionState {

    /**
     * Canonical whitelist of valid permission modes.
     * Shared across SessionHandler (payload validation) and ClaudeSession (mode resolution).
     */
    public static final Set<String> VALID_PERMISSION_MODES;
    public static final Set<String> VALID_CLAUDE_INVOCATION_MODES;
    public static final Set<String> VALID_PROVIDERS;
    static {
        Set<String> modes = new HashSet<>();
        modes.add(CommonConstants.PERMISSION_MODE_DEFAULT);
        modes.add(CommonConstants.PERMISSION_MODE_PLAN);
        modes.add(CommonConstants.PERMISSION_MODE_ACCEPT_EDITS);
        modes.add(CommonConstants.PERMISSION_MODE_AUTO_EDIT);
        modes.add(CommonConstants.PERMISSION_MODE_BYPASS);
        VALID_PERMISSION_MODES = Collections.unmodifiableSet(modes);

        Set<String> invocationModes = new HashSet<>();
        invocationModes.add(CommonConstants.INVOCATION_MODE_SDK);
        invocationModes.add(CommonConstants.INVOCATION_MODE_CLI);
        VALID_CLAUDE_INVOCATION_MODES = Collections.unmodifiableSet(invocationModes);

        Set<String> providers = new HashSet<>();
        providers.add(CommonConstants.PROVIDER_CLAUDE);
        providers.add(CommonConstants.PROVIDER_CODEX);
        VALID_PROVIDERS = Collections.unmodifiableSet(providers);
    }

    /**
     * Check whether the given mode string is a recognized permission mode.
     */
    public static boolean isValidPermissionMode(String mode) {
        return mode != null && VALID_PERMISSION_MODES.contains(mode.trim());
    }

    public static boolean isValidClaudeInvocationMode(String mode) {
        return mode != null && VALID_CLAUDE_INVOCATION_MODES.contains(mode.trim());
    }

    // Session identifiers
    private String sessionId;
    private String channelId;
    private volatile String runtimeSessionEpoch = UUID.randomUUID().toString();

    // Session state — 在 handler/EDT/reader 多线程间读写,加 volatile 保证可见性
    // (setBusy/setLoading 等在 handler 线程,getMessagesReference 迭代在 reader/EDT)。
    private volatile boolean busy = false;
    private volatile boolean loading = false;
    private volatile String error = null;
    private volatile ClaudeSession.SessionCallback.QueueDisplayState queueDisplayState =
            ClaudeSession.SessionCallback.QueueDisplayState.NONE;
    private volatile int queueAheadCount = 0;

    // Message history — CopyOnWriteArrayList:消息以追加为主、偶发清空,读端(getMessages/迭代)
    // 可能发生在 reader/EDT/handler 多线程,原生 ArrayList 在并发 add + 拷贝迭代时会
    // ConcurrentModificationException 或内部数组损坏。COW 以写时复制换取读端无锁安全。
    private final List<ClaudeSession.Message> messages = new CopyOnWriteArrayList<>();

    // Session metadata — cwd is written in handler thread before send(), read inside send();
    // the happens-before from CompletableFuture.runAsync guarantees visibility, so volatile is not required.
    private String summary = null;
    private long lastModifiedTime = System.currentTimeMillis();
    private String cwd = null;

    // Configuration fields below are volatile because set_mode / set_model / set_provider
    // and send_message may execute on different async handler threads with no other
    // happens-before guarantee between them.
    // Default to PERMISSION_MODE_DEFAULT ("default" — prompt on each tool call).
    // "bypassPermissions" must be an explicit, informed opt-in — see security remediation A:
    // shipping bypass as the out-of-the-box default removed the only confirmation gate for
    // AI-issued commands. NOTE: intentionally NOT CommonConstants.DEFAULT_PERMISSION_MODE,
    // which is "acceptEdits" (a 0.4.x default retained for back-compat, not the safe default).
    private volatile String permissionMode = CommonConstants.PERMISSION_MODE_DEFAULT;
    private volatile String model = CommonConstants.DEFAULT_MODEL;
    private volatile String provider = CommonConstants.DEFAULT_PROVIDER;
    private volatile String claudeInvocationMode = null;
    private volatile String permissionSessionId = null;
    // Reasoning effort (thinking depth). Null means "do not override SDK/settings".
    private volatile String reasoningEffort = null;
    // Codex service tier: null = use Codex defaults, "fast" = Codex /fast.
    private volatile String codexServiceTier = null;
    // Context window override from frontend (null = use backend default)
    private volatile Integer contextWindowOverride;

    // Slash commands — volatile for cross-thread visibility (same reason as permissionMode/model/provider)
    private volatile List<String> slashCommands = new ArrayList<>();

    // PSI context collection toggle
    private boolean psiContextEnabled = true;
    private final AtomicLong pendingSendInvalidationEpoch = new AtomicLong(0);

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getChannelId() {
        return channelId;
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getError() {
        return error;
    }

    public ClaudeSession.SessionCallback.QueueDisplayState getQueueDisplayState() {
        return queueDisplayState;
    }

    public int getQueueAheadCount() {
        return queueAheadCount;
    }

    public List<ClaudeSession.Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public List<ClaudeSession.Message> getMessagesReference() {
        return messages;
    }

    public String getSummary() {
        return summary;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public String getCwd() {
        return cwd;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getClaudeInvocationMode() {
        return claudeInvocationMode;
    }

    public String getPermissionSessionId() {
        return permissionSessionId;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public String getCodexServiceTier() {
        return codexServiceTier;
    }

    public String getRuntimeSessionEpoch() {
        return runtimeSessionEpoch;
    }

    public List<String> getSlashCommands() {
        return new ArrayList<>(slashCommands);
    }



    public boolean isPsiContextEnabled() {
        return psiContextEnabled;
    }

    public long capturePendingSendInvalidationEpoch() {
        return pendingSendInvalidationEpoch.get();
    }

    public long invalidatePendingSendOperations() {
        return pendingSendInvalidationEpoch.incrementAndGet();
    }

    public boolean isPendingSendOperationCurrent(long epoch) {
        return pendingSendInvalidationEpoch.get() == epoch;
    }

    // Setters
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setQueueDisplayState(ClaudeSession.SessionCallback.QueueDisplayState queueDisplayState) {
        this.queueDisplayState = queueDisplayState != null
                ? queueDisplayState
                : ClaudeSession.SessionCallback.QueueDisplayState.NONE;
    }

    public void setQueueAheadCount(int queueAheadCount) {
        this.queueAheadCount = Math.max(0, queueAheadCount);
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public void setPermissionMode(String permissionMode) {
        if (permissionMode != null && !VALID_PERMISSION_MODES.contains(permissionMode.trim())) {
            // Reject unrecognized modes silently to prevent injection of arbitrary strings
            return;
        }
        this.permissionMode = permissionMode;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setProvider(String provider) {
        if (provider == null) {
            return;
        }
        String trimmed = provider.trim();
        if (VALID_PROVIDERS.contains(trimmed)) {
            this.provider = trimmed;
        }
    }

    public void setClaudeInvocationMode(String claudeInvocationMode) {
        if (claudeInvocationMode == null) {
            return;
        }
        String trimmed = claudeInvocationMode.trim();
        if (VALID_CLAUDE_INVOCATION_MODES.contains(trimmed)) {
            this.claudeInvocationMode = trimmed;
        }
    }

    public void setPermissionSessionId(String permissionSessionId) {
        if (permissionSessionId == null) {
            return;
        }
        String trimmed = permissionSessionId.trim();
        if (!trimmed.isEmpty()) {
            this.permissionSessionId = trimmed;
        }
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public void setCodexServiceTier(String codexServiceTier) {
        this.codexServiceTier = codexServiceTier;
    }

    public void setRuntimeSessionEpoch(String runtimeSessionEpoch) {
        if (runtimeSessionEpoch == null || runtimeSessionEpoch.trim().isEmpty()) {
            this.runtimeSessionEpoch = UUID.randomUUID().toString();
            return;
        }
        this.runtimeSessionEpoch = runtimeSessionEpoch;
    }

    public String rotateRuntimeSessionEpoch() {
        String newEpoch = UUID.randomUUID().toString();
        this.runtimeSessionEpoch = newEpoch;
        return newEpoch;
    }

    public void setSlashCommands(List<String> slashCommands) {
        this.slashCommands = new ArrayList<>(slashCommands);
    }



    public void setPsiContextEnabled(boolean psiContextEnabled) {
        this.psiContextEnabled = psiContextEnabled;
    }

    public Integer getContextWindowOverride() {
        return contextWindowOverride;
    }

    /**
     * Get effective max tokens: use frontend override if set, capped at model's actual limit.
     * For known models (in MODEL_CONTEXT_LIMITS), the override is capped.
     * For unknown models, the override is trusted as-is.
     */
    public int getEffectiveMaxTokens() {
        if (contextWindowOverride != null && contextWindowOverride > 0) {
            if (!com.github.claudecodegui.handler.provider.ModelProviderHandler.isKnownModel(null, model)) {
                return contextWindowOverride;
            }
            int modelMaxLimit = com.github.claudecodegui.handler.provider.ModelProviderHandler
                    .getModelContextLimit(model);
            // Cap at model's actual limit for known models
            return Math.min(contextWindowOverride, modelMaxLimit);
        }
        return com.github.claudecodegui.handler.provider.ModelProviderHandler.getModelContextLimit(model);
    }

    public void setContextWindowOverride(Integer contextWindowOverride) {
        this.contextWindowOverride = contextWindowOverride;
    }

    /**
     * Add a message to the history.
     */
    public void addMessage(ClaudeSession.Message message) {
        messages.add(message);
    }

    /**
     * Clear all messages.
     */
    public void clearMessages() {
        messages.clear();
    }

    /**
     * Update the last modified time to the current time.
     */
    public void updateLastModifiedTime() {
        this.lastModifiedTime = System.currentTimeMillis();
    }
}
