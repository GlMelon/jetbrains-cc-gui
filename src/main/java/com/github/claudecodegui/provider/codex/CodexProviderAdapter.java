package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;

public class CodexProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.CODEX, "Codex");

    @Override
    public ProviderId providerId() {
        return ProviderId.CODEX;
    }

    @Override
    public ProviderViewModel viewModel() {
        return VIEW_MODEL;
    }
}
