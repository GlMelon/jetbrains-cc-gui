package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;

import java.util.List;

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
     * B7: OpenCode 历史持久化与回放尚在规划(设计 §11/§8,当前无与 ClaudeSessionQueryService/CodexHistoryReader
     * 对等的 reader)。返回空列表优雅降级,避免 {@code loadHistorySession} 经默认接口抛
     * UnsupportedOperationException 阻断历史加载流程。OpenCode 历史 reader 就绪后改为委托读取。
     */
    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return List.of();
    }

    private OpenCodeSDKBridge requireBridge() {
        if (openCodeSDKBridge == null) {
            throw new IllegalStateException("OpenCode SDK bridge is required for session routing");
        }
        return openCodeSDKBridge;
    }
}
