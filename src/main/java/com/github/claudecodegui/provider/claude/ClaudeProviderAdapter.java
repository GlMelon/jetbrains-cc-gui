package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;

import java.util.List;

public class ClaudeProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.CLAUDE, "Claude");
    private final ClaudeSDKBridge claudeSDKBridge;

    public ClaudeProviderAdapter() {
        this(null);
    }

    public ClaudeProviderAdapter(ClaudeSDKBridge claudeSDKBridge) {
        this.claudeSDKBridge = claudeSDKBridge;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.CLAUDE;
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

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return requireBridge().getSessionMessages(sessionId, cwd);
    }

    private ClaudeSDKBridge requireBridge() {
        if (claudeSDKBridge == null) {
            throw new IllegalStateException("Claude SDK bridge is required for session routing");
        }
        return claudeSDKBridge;
    }
}
