package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;

import java.util.List;

public class CodexProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.CODEX, "Codex");
    private final CodexSDKBridge codexSDKBridge;

    public CodexProviderAdapter() {
        this(null);
    }

    public CodexProviderAdapter(CodexSDKBridge codexSDKBridge) {
        this.codexSDKBridge = codexSDKBridge;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.CODEX;
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
    public void cleanupProviderSession(String sessionId, String cwd) {
        requireBridge().clearCachedThread(sessionId, cwd);
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return requireBridge().getSessionMessages(sessionId, cwd);
    }

    private CodexSDKBridge requireBridge() {
        if (codexSDKBridge == null) {
            throw new IllegalStateException("Codex SDK bridge is required for session routing");
        }
        return codexSDKBridge;
    }
}
