package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Centralizes provider-specific bridge routing for session operations.
 */
public class SessionProviderRouter {

    private final ProviderRegistry providerRegistry;

    public SessionProviderRouter(ClaudeSDKBridge claudeSDKBridge, CodexSDKBridge codexSDKBridge) {
        this(new ProviderRegistry(List.of(
                new ClaudeProviderAdapter(claudeSDKBridge),
                new CodexProviderAdapter(codexSDKBridge)
        )));
    }

    public SessionProviderRouter(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public JsonObject launchChannel(String provider, String channelId, String sessionId, String cwd) {
        return adapter(provider).launchChannel(channelId, sessionId, cwd);
    }

    public void interruptChannel(String provider, String channelId) {
        adapter(provider).interruptChannel(channelId);
    }

    public void cleanupProviderSession(String provider, String sessionId, String cwd) {
        adapter(provider).cleanupProviderSession(sessionId, cwd);
    }

    public List<JsonObject> getSessionMessages(String provider, String sessionId, String cwd) {
        return adapter(provider).getSessionMessages(sessionId, cwd);
    }

    private ProviderAdapter adapter(String provider) {
        // 直接用 ProviderId.of(内部 trim/lowercase 归一)路由,消除手写 provider 解析(总则五·开闭 / E3)。
        // 未知 provider 由 ProviderRegistry.require fail-fast 抛异常,对齐 registry 设计意图,
        // 取代原先「未知静默 fallback 到 CLAUDE」的偏离行为。
        return providerRegistry.require(ProviderId.of(provider));
    }
}
