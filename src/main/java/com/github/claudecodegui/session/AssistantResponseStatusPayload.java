package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * UI-ready assistant response status payload.
 *
 * <p>The backend owns phase semantics; the frontend renders these fields. 显示文案的 i18n
 * 由前端按 {@code descriptionKey}(语义 key)查 webview locale——后端 Bundle 跟随 IDE 界面
 * 语言,与 webview 语言设置可能不一致,故 title/description 仅作 fallback。</p>
 */
public record AssistantResponseStatusPayload(
        String phase,
        String providerLabel,
        String title,
        String description,
        /** 前端 i18n 语义 key(常规=phase value;特殊:apiRetry/cancelled);null 表示无语义 key,前端直接用 description。 */
        String descriptionKey,
        /** api_retry 重试次序(1-based);null/<=0 表示未知(前端显示 "?")。 */
        Integer attempt,
        /** api_retry 最大重试次数;null/<=0 表示未知。 */
        Integer maxRetries,
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
                safePhase.value(),
                null,
                null,
                elapsedMs,
                safePhase.active()
        );
    }

    /**
     * 带 description 覆盖的工厂:用于需要动态文案的场景。phase 的 title 仍取
     * Bundle,description 用 {@code descriptionOverride}(为 null 时回退 Bundle 默认)。
     * descriptionKey 仍为 phase value(覆盖文案不走前端 i18n)。
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
                null,
                null,
                null,
                elapsedMs,
                safePhase.active()
        );
    }

    /**
     * api_retry 重试态工厂:title 仍取 understanding("等待模型响应"),description 注入重试
     * 计数(attempt/max),phase 为 {@link AssistantResponsePhase#API_RETRY},供前端据此切琥珀
     * 警示色,使 CLI 静默指数退避重试期间(init 后挂起)可感知。attempt/max 无效时计数回退为 "?"。
     *
     * @param provider            provider code
     * @param turnStartedAtMillis turn 起始毫秒(前端计时器自算,可传 0)
     * @param attempt             当前重试次序(1-based,<=0 视为未知)
     * @param maxRetries          最大重试次数(<=0 视为未知)
     */
    public static AssistantResponseStatusPayload forApiRetry(
            String provider, long turnStartedAtMillis, int attempt, int maxRetries) {
        long elapsedMs = turnStartedAtMillis > 0
                ? Math.max(0L, System.currentTimeMillis() - turnStartedAtMillis)
                : 0L;
        String attemptStr = attempt > 0 ? String.valueOf(attempt) : "?";
        String maxStr = maxRetries > 0 ? String.valueOf(maxRetries) : "?";
        String description = ClaudeCodeGuiBundle.message(
                AssistantResponsePhase.API_RETRY.descriptionKey(), attemptStr, maxStr);
        return new AssistantResponseStatusPayload(
                AssistantResponsePhase.API_RETRY.value(),
                safeProviderLabel(providerLabel(provider)),
                ClaudeCodeGuiBundle.message(AssistantResponsePhase.API_RETRY.titleKey()),
                description,
                "apiRetry",
                attempt,
                maxRetries,
                elapsedMs,
                AssistantResponsePhase.API_RETRY.active()
        );
    }

    /**
     * 从 api_retry phase content(格式 {@code api_retry[:attempt[:maxRetries]]})构造重试态 payload。
     * content 无冒号或数字解析失败时,对应字段回退 -1(进而显示为 "?")。
     * 调用方应先用 {@code content.startsWith("api_retry")} 守卫。
     */
    public static AssistantResponseStatusPayload forApiRetryFromContent(
            String provider, long turnStartedAtMillis, String content) {
        int attempt = -1;
        int maxRetries = -1;
        if (content != null) {
            int colon = content.indexOf(':');
            if (colon >= 0) {
                String[] parts = content.substring(colon + 1).split(":");
                attempt = safeParseInt(parts, 0);
                maxRetries = safeParseInt(parts, 1);
            }
        }
        return forApiRetry(provider, turnStartedAtMillis, attempt, maxRetries);
    }

    private static int safeParseInt(String[] parts, int index) {
        if (index < 0 || index >= parts.length) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
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

    /**
     * 用户取消时的工厂:phase 为 {@link AssistantResponsePhase#ERROR},descriptionKey 为
     * "cancelled"(前端 i18n 覆盖"用户已取消")。用于 interrupt 后向前端展示友好的取消提示。
     */
    public static AssistantResponseStatusPayload forCancelled(String provider) {
        return new AssistantResponseStatusPayload(
                AssistantResponsePhase.ERROR.value(),
                safeProviderLabel(providerLabel(provider)),
                ClaudeCodeGuiBundle.message(AssistantResponsePhase.ERROR.titleKey()),
                ClaudeCodeGuiBundle.message("assistant.response.cancelled"),
                "cancelled",
                null,
                null,
                0L,
                AssistantResponsePhase.ERROR.active()
        );
    }
}
