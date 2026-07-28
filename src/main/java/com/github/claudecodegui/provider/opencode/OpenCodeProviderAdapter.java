package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * OpenCode provider adapter: delegates all operations to {@link OpenCodeSDKBridge}.
 */
public class OpenCodeProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.OPENCODE, "OpenCode");
    private final OpenCodeSDKBridge openCodeSDKBridge;

    public OpenCodeProviderAdapter() {
        this(null);
    }

    public OpenCodeProviderAdapter(OpenCodeSDKBridge openCodeSDKBridge) {
        this.openCodeSDKBridge = openCodeSDKBridge;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.OPENCODE;
    }

    @Override
    public ProviderViewModel viewModel() {
        return VIEW_MODEL;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(
                ProviderCapability.SDK_SESSION,
                ProviderCapability.CLI_SESSION,
                ProviderCapability.STREAMING,
                ProviderCapability.REASONING_THINKING,
                ProviderCapability.HISTORY,
                ProviderCapability.SKILLS,
                ProviderCapability.MCP
        );
    }

    @Override
    public JsonObject launchChannel(String channelId, String sessionId, String cwd) {
        return requireBridge().launchChannel(channelId, sessionId, cwd);
    }

    @Override
    public void interruptChannel(String channelId) {
        requireBridge().interruptChannel(channelId);
    }

    /**
     * B7: OpenCode 不缓存 thread(serve 守护进程由 {@code OpenCodeDaemonCoordinator} 统一管理生命周期,见设计 §8.1),
     * 无需像 Codex 那样清空 thread 缓存。此 override 显式 no-op,对称 {@link ProviderAdapter} 接口,
     * 避免会话清理流程误触其他 provider 的清理逻辑。OpenCode 历史持久化就绪后此处可接入清理。
     */
    @Override
    public void cleanupProviderSession(String sessionId, String cwd) {
        // 有意 no-op:OpenCode 无 per-session thread 缓存。
    }

    /**
     * OpenCode history is stored in its local SQLite database; the bridge reads
     * it through the ai-bridge history service and returns normalized messages.
     */
    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return requireBridge().getSessionMessages(sessionId, cwd);
    }

    private OpenCodeSDKBridge requireBridge() {
        if (openCodeSDKBridge == null) {
            throw new IllegalStateException("OpenCode SDK bridge is required for session routing");
        }
        return openCodeSDKBridge;
    }
}
