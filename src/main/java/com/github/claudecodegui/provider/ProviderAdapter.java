package com.github.claudecodegui.provider;

import com.google.gson.JsonObject;

import java.util.List;

public interface ProviderAdapter {
    ProviderId providerId();

    ProviderViewModel viewModel();

    default JsonObject launchChannel(String channelId, String sessionId, String cwd) {
        throw new UnsupportedOperationException("launchChannel is not supported by " + providerId().value());
    }

    default void interruptChannel(String channelId) {
        throw new UnsupportedOperationException("interruptChannel is not supported by " + providerId().value());
    }

    default void cleanupProviderSession(String sessionId, String cwd) {
    }

    default List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        throw new UnsupportedOperationException("getSessionMessages is not supported by " + providerId().value());
    }
}
