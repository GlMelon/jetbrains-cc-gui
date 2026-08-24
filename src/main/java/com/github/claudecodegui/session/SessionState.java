package com.github.claudecodegui.session;


import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.PlatformUtils;

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
    public static final Set<String> VALID_PROVIDERS;
    static {
        Set<String> modes = new HashSet<>();
        modes.add(CommonConstants.PERMISSION_MODE_DEFAULT);
        modes.add(CommonConstants.PERMISSION_MODE_PLAN);
        modes.add(CommonConstants.PERMISSION_MODE_ACCEPT_EDITS);
        modes.add(CommonConstants.PERMISSION_MODE_AUTO_EDIT);
        modes.add(CommonConstants.PERMISSION_MODE_BYPASS);
        // omp model-role modes (`omp --model smol|slow`); only offered by the
        // webview for the omp provider, but validated here so set_mode accepts them.
        modes.add("smol");
        modes.add("slow");
        VALID_PERMISSION_MODES = Collections.unmodifiableSet(modes);

        Set<String> providers = new HashSet<>();
        providers.add(CommonConstants.PROVIDER_CLAUDE);
        providers.add(CommonConstants.PROVIDER_CODEX);
        // B5: OpenCode 作为第三 provider 纳入校验白名单,否则 setProvider("opencode") 被拒(provider 选择无法持久化)。
        providers.add(CommonConstants.PROVIDER_OPENCODE);
        // grok/kimi/pi:纯 CLI provider(上游 CliToolId),纳入校验白名单供 setProvider 持久化。
        providers.add(CommonConstants.PROVIDER_GROK);
        providers.add(CommonConstants.PROVIDER_KIMI);
        providers.add(CommonConstants.PROVIDER_PI);
        // omp/dsh:上游 v0.5.4 新增的纯 CLI provider,纳入白名单供 setProvider 持久化。
        providers.add(CommonConstants.PROVIDER_OMP);
        providers.add(CommonConstants.PROVIDER_DSH);
        VALID_PROVIDERS = Collections.unmodifiableSet(providers);
    }

    /**
     * Check whether the given mode string is a recognized permission mode.
     */
    public static boolean isValidPermissionMode(String mode) {
        return mode != null && VALID_PERMISSION_MODES.contains(mode.trim());
    }


    /**
     * Check whether the given DSH agent preset id is recognized.
     */
    public static boolean isValidDshPreset(String preset) {
        if (preset == null) {
            return false;
        }
        String normalized = preset.trim();
        // Keep aligned with DSH_PRESET_IDS in ai-bridge/services/dsh/preset-overlay.js
        // (router-standard ships with the dsh-routing-suite user presets).
        return normalized.isEmpty()
                || Set.of("standard", "code", "minimal", "cordis", "router-standard").contains(normalized)
                || discoverUserDshPresetIds().contains(normalized);
    }

    public static List<String> discoverUserDshPresetIds() {
        List<String> ids = new ArrayList<>();
        String dshHome = System.getenv("DSH_HOME");
        java.nio.file.Path dshRoot = dshHome != null && !dshHome.trim().isEmpty()
                ? java.nio.file.Paths.get(dshHome.trim())
                : java.nio.file.Paths.get(PlatformUtils.getHomeDirectory(), ".dsh");
        java.nio.file.Path root = dshRoot.resolve(".agent-presets");
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                     java.nio.file.Files.newDirectoryStream(root)) {
            for (java.nio.file.Path entry : stream) {
                if (java.nio.file.Files.isDirectory(entry)
                        && java.nio.file.Files.isRegularFile(entry.resolve("agent.cordis.yml"))) {
                    ids.add(entry.getFileName().toString());
                }
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    // Session identifiers
    private volatile String sessionId;
    private volatile String channelId;
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
    private volatile String permissionSessionId = null;
    // Reasoning effort (thinking depth). Null means "do not override SDK/settings".
    private volatile String reasoningEffort = null;
    // Codex service tier: null = use Codex defaults, "fast" = Codex /fast.
    private volatile String codexServiceTier = null;
    // Context window override from frontend (null = use backend default)
    private volatile Integer contextWindowOverride;
    // DSH agent preset id ("" = no overlay; see ai-bridge/services/dsh/preset-overlay.js)
    private volatile String dshPreset = "";

    // Slash commands — volatile for cross-thread visibility (same reason as permissionMode/model/provider)
    private volatile List<String> slashCommands = new ArrayList<>();
    private volatile SessionSkillSnapshot skillSnapshot = SessionSkillSnapshot.empty();

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


    public String getPermissionSessionId() {
        return permissionSessionId;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public String getCodexServiceTier() {
        return codexServiceTier;
    }

    public String getDshPreset() {
        return dshPreset;
    }

    public String getRuntimeSessionEpoch() {
        return runtimeSessionEpoch;
    }

    public List<String> getSlashCommands() {
        return new ArrayList<>(slashCommands);
    }

    public SessionSkillSnapshot getSkillSnapshot() {
        return skillSnapshot;
    }

    public void setSkillSnapshot(SessionSkillSnapshot skillSnapshot) {
        this.skillSnapshot = skillSnapshot == null ? SessionSkillSnapshot.empty() : skillSnapshot;
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
        this.model = normalizeRetiredModelId(model);
    }

    /**
     * Migrate retired Claude model ids to their live replacement on write.
     *
     * <p>Persisted tab state (.idea/claudeCodeTabState.xml) and history sessions keep
     * whatever model id was saved forever. When a model is retired from the API
     * (sonnet-4-6, sonnet-4-7, ...), restoring such a tab would otherwise spawn a CLI
     * pinned to a dead model that fails on every send ("It may not exist or you may
     * not have access to it") - see #1678. Migrating here self-heals restored tabs
     * without touching the persisted XML.</p>
     *
     * @param model raw model id (may be null, blank, carry a [1m] suffix, or be retired)
     * @return the model id to store - retired ids mapped to their live replacement,
     *         anything else (including non-Claude ids) passed through unchanged
     */
    public static String normalizeRetiredModelId(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        if (trimmed.isEmpty()) {
            // Blank input normalizes to "" like every other path returns trimmed.
            return trimmed;
        }
        String base = trimmed;
        boolean oneM = false;
        if (base.endsWith("[1m]")) {
            base = base.substring(0, base.length() - "[1m]".length());
            oneM = true;
        }
        switch (base) {
            case "claude-sonnet-4-6":
            case "claude-sonnet-4-7":
                base = "claude-sonnet-5";
                break;
            case "claude-opus-4-6":
                base = "claude-opus-4-8";
                break;
            default:
                return trimmed;
        }
        return oneM ? base + "[1m]" : base;
    }

    public void setProvider(String provider) {
        if (provider == null) {
            return;
        }
        String trimmed = provider.trim();
        if (VALID_PROVIDERS.contains(trimmed)) {
            // 跨 provider 切换时清空 sessionId:三 provider 的 session 协议/格式互不兼容
            // (Claude/Codex=UUID, OpenCode=ses_xxx),复用会让 claude --resume 收到非 UUID 崩溃。
            // 同 provider 内(SDK↔CLI 调用模式切换)格式一致,保留以支持会话续接。
            if (!trimmed.equals(this.provider)) {
                this.sessionId = null;
            }
            this.provider = trimmed;
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

    public void setDshPreset(String preset) {
        if (isValidDshPreset(preset)) {
            this.dshPreset = preset.trim();
        }
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
     * Get effective max tokens: use the context window override if set, otherwise the model's default limit.
     *
     * <p>override 唯一写入点是 {@code ModelProviderHandler.applyModelChange},其值已是 resolver
     * 权威解析结果(claude 路径 = min(前端请求, registry/角色上限,1M 判定);codex 路径 = registry
     * 条目窗口),此处直接信任,不再按 model 重复 cap。重复 cap 会因 state.model 已剥容量后缀、
     * 且 registry.find 同样剥后缀(带 [1m] 的独立条目永远查不到),对「base 条目 200k +
     * [1m] 条目 1M」的双条目自定义模型命中 base 条目,把 resolver 解析出的 1M 错误压回 200k。
     */
    public int getEffectiveMaxTokens() {
        if (contextWindowOverride != null && contextWindowOverride > 0) {
            return contextWindowOverride;
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

    public void replaceMessages(List<ClaudeSession.Message> replacement) {
        messages.clear();
        if (replacement != null && !replacement.isEmpty()) {
            messages.addAll(replacement);
        }
    }

    public void prependMessages(List<ClaudeSession.Message> prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return;
        }
        messages.addAll(0, prefix);
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
