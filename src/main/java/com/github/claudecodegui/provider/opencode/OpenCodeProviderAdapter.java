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

    private OpenCodeSDKBridge requireBridge() {
        if (openCodeSDKBridge == null) {
            throw new IllegalStateException("OpenCode SDK bridge is required for session routing");
        }
        return openCodeSDKBridge;
    }
}
