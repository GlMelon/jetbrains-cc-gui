package com.github.claudecodegui.provider;

import java.util.Set;

/**
 * 纯 CLI marker provider(Grok / Kimi / Pi)的通用轻量适配器。
 *
 * <p>这三家 provider 仅支持 CLI(marker 协议流式,经各自 {@code *CliSession} 把上游
 * {@code grok run} / {@code kimi} / {@code pi} 输出归一为 MSG_*),<b>不</b>提供
 * history / mcp / rewind / skills 等横切能力——上游 CLI 无会话历史读取接口。故:
 * <ul>
 *   <li>{@link #capabilities()} 仅声明 {@link ProviderCapability#CLI_SESSION}
 *       + {@link ProviderCapability#STREAMING},对齐 {@link ProviderDescriptor#cliBuiltin};</li>
 *   <li>{@link #getSessionMessages(String, String)} 走接口默认(抛
 *       {@link UnsupportedOperationException})——因不声明 {@link ProviderCapability#HISTORY},
 *       前端历史 UI 不会对这三家触发,默认实现仅为 fail-fast 兜底,取代原先静默 fallback 到 CLAUDE。</li>
 * </ul>
 * <p>三家 adapter 逻辑完全相同(仅 {@link ProviderId} 与显示名不同),故共用一个参数化类,
 * 避免三份重复代码;与 {@link com.github.claudecodegui.provider.claude.ClaudeProviderAdapter}
 * 等"全功能"适配器(每家独立类 + historyService)形成对照。
 */
public final class CliOnlyProviderAdapter implements ProviderAdapter {
    private final ProviderId providerId;
    private final ProviderViewModel viewModel;

    public CliOnlyProviderAdapter(ProviderId providerId, String displayLabel) {
        this.providerId = providerId;
        this.viewModel = new ProviderViewModel(providerId, displayLabel);
    }

    @Override
    public ProviderId providerId() {
        return providerId;
    }

    @Override
    public ProviderViewModel viewModel() {
        return viewModel;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING);
    }
}
