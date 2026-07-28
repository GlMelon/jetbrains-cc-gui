package com.github.claudecodegui.provider;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

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

    /**
     * 该 Provider 声明支持的横切能力维度（F1 capability descriptor）。
     *
     * <p>默认空集以保持向后兼容：未显式声明能力的适配器视为不支持任何 {@link ProviderCapability}，
     * 不影响既有 launchChannel/getSessionMessages 等方法级行为。各 Provider 应按实际支持情况覆盖。
     */
    default Set<ProviderCapability> capabilities() {
        return Set.of();
    }

    /**
     * 是否支持指定能力，委托 {@link #capabilities()}。
     */
    default boolean supports(ProviderCapability capability) {
        return capabilities().contains(capability);
    }
}
