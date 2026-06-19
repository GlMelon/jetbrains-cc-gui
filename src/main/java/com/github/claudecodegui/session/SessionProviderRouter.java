package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
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
        return providerRegistry.require(providerId(provider));
    }

    private ProviderId providerId(String provider) {
        if (CommonConstants.PROVIDER_CODEX.equals(provider)) {
            return ProviderId.CODEX;
        }
        return ProviderId.CLAUDE;
    }
}
