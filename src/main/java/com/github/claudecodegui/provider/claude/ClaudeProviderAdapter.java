package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;

public class ClaudeProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.CLAUDE, "Claude");

    @Override
    public ProviderId providerId() {
        return ProviderId.CLAUDE;
    }

    @Override
    public ProviderViewModel viewModel() {
        return VIEW_MODEL;
    }
}
