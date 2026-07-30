package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

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

    @Override
    public void cleanupProviderSession(String sessionId, String cwd) {
        requireBridge().clearCachedThread(sessionId, cwd);
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return requireBridge().getSessionMessages(sessionId, cwd);
    }

    @Override
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        return requireBridge().getInitialSessionHistory(sessionId, cwd);
    }

    private CodexSDKBridge requireBridge() {
        if (codexSDKBridge == null) {
            throw new IllegalStateException("Codex SDK bridge is required for session routing");
        }
        return codexSDKBridge;
    }
}

