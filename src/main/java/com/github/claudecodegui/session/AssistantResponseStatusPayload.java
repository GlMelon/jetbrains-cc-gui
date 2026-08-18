package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * UI-ready assistant response status payload.
 *
 * <p>The backend owns phase semantics and display text; the frontend only renders these fields.</p>
 */
public record AssistantResponseStatusPayload(
        String phase,
        String providerLabel,
        String title,
        String description,
        long elapsedMs,
        boolean active
) {
    private static final String PROVIDER_LABEL_AI = "AI";

    public static AssistantResponseStatusPayload forProvider(
            AssistantResponsePhase phase,
            String provider,
            long turnStartedAtMillis
    ) {
        return forProviderLabel(phase, providerLabel(provider), turnStartedAtMillis);
    }

    public static AssistantResponseStatusPayload forProviderLabel(
            AssistantResponsePhase phase,
            String providerLabel,
            long turnStartedAtMillis
    ) {
        AssistantResponsePhase safePhase = phase != null ? phase : AssistantResponsePhase.CONNECTING;
        long elapsedMs = turnStartedAtMillis > 0
                ? Math.max(0L, System.currentTimeMillis() - turnStartedAtMillis)
                : 0L;
        return new AssistantResponseStatusPayload(
                safePhase.value(),
                safeProviderLabel(providerLabel),
                ClaudeCodeGuiBundle.message(safePhase.titleKey()),
                ClaudeCodeGuiBundle.message(safePhase.descriptionKey()),
                elapsedMs,
                safePhase.active()
        );
    }

    /**
     * 带 description 覆盖的工厂:用于 api_retry 等需要动态文案的场景。phase 的 title 仍取
     * Bundle,description 用 {@code descriptionOverride}(为 null 时回退 Bundle 默认)。
     */
    public static AssistantResponseStatusPayload forProviderWithDescription(
            AssistantResponsePhase phase,
            String provider,
            long turnStartedAtMillis,
            String descriptionOverride
    ) {
        AssistantResponsePhase safePhase = phase != null ? phase : AssistantResponsePhase.CONNECTING;
        long elapsedMs = turnStartedAtMillis > 0
                ? Math.max(0L, System.currentTimeMillis() - turnStartedAtMillis)
                : 0L;
        String description = descriptionOverride != null && !descriptionOverride.isEmpty()
                ? descriptionOverride
                : ClaudeCodeGuiBundle.message(safePhase.descriptionKey());
        return new AssistantResponseStatusPayload(
                safePhase.value(),
                safeProviderLabel(providerLabel(provider)),
                ClaudeCodeGuiBundle.message(safePhase.titleKey()),
                description,
                elapsedMs,
                safePhase.active()
        );
    }

    private static String providerLabel(String provider) {
        if (CommonConstants.PROVIDER_CODEX.equals(provider)
                || CommonConstants.PROVIDER_OPENCODE.equals(provider)
                || CommonConstants.PROVIDER_CLAUDE.equals(provider)) {
            return ProviderType.fromValue(provider)
                    .map(ProviderType::displayLabel)
                    .orElse(PROVIDER_LABEL_AI);
        }
        return PROVIDER_LABEL_AI;
    }

    private static String safeProviderLabel(String providerLabel) {
        if (providerLabel == null || providerLabel.trim().isEmpty()) {
            return PROVIDER_LABEL_AI;
        }
        return providerLabel.trim();
    }
}
