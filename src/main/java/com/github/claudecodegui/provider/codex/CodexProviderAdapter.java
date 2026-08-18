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
    private final CodexHistoryService historyService;

    public CodexProviderAdapter() {
        this.historyService = new CodexHistoryService();
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
                ProviderCapability.CLI_SESSION,
                ProviderCapability.STREAMING,
                ProviderCapability.REASONING_THINKING,
                ProviderCapability.HISTORY,
                ProviderCapability.SKILLS,
                ProviderCapability.MCP
        );
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return historyService.getSessionMessages(sessionId, cwd);
    }

    @Override
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        return historyService.getInitialSessionHistory(sessionId, cwd);
    }
}
