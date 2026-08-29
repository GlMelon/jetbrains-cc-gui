package com.github.claudecodegui.provider;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 纯 CLI marker provider(Grok / Kimi / Pi / OMP / DSH)的通用轻量适配器。
 *
 * <p>这几家 provider 仅支持 CLI(marker 协议流式,经各自 {@code *CliSession} 把上游 CLI 输出
 * 归一为 MSG_*),<b>不</b>提供 mcp / rewind / skills 等横切能力。历史读取是可选项:
 * <ul>
 *   <li>注入 {@code sessionMessagesLoader} 的 provider(如 kimi/grok/pi,各自 HistoryReader
 *       读上游 CLI 本地会话落盘)声明 {@link ProviderCapability#HISTORY},
 *       {@link #getSessionMessages(String, String)} 走 loader——支撑历史面板「点会话回load」
 *       (SessionProviderRouter.getInitialSessionHistory,未实现时实测对 kimi 抛
 *       UnsupportedOperationException 前端报 Error loading session);</li>
 *   <li>不注入的 provider(omp/dsh,上游无会话落盘体系)走接口默认 fail-fast。</li>
 * </ul>
 *
 * <p>各家 adapter 逻辑完全相同(仅 {@link ProviderId} 与显示名不同),故共用一个参数化类;
 * 与 {@link com.github.claudecodegui.provider.claude.ClaudeProviderAdapter}
 * 等"全功能"适配器(每家独立类 + historyService)形成对照。
 */
public final class CliOnlyProviderAdapter implements ProviderAdapter {

    /** 会话消息读取器:(sessionId, cwd) → 前端 Claude 兼容消息;null=不支持历史读取。 */
    public interface SessionMessagesLoader extends BiFunction<String, String, List<JsonObject>> {
    }

    /** 分页历史读取器:(sessionId, cwd) → 带分页元数据的初始页;null=初始页走全量(pageInfo=null)。 */
    public interface SessionHistoryLoader extends BiFunction<String, String, SessionHistoryLoadResult> {
    }

    private final ProviderId providerId;
    private final ProviderViewModel viewModel;
    private final SessionMessagesLoader sessionMessagesLoader;
    private final SessionHistoryLoader sessionHistoryLoader;

    public CliOnlyProviderAdapter(ProviderId providerId, String displayLabel) {
        this(providerId, displayLabel, null);
    }

    public CliOnlyProviderAdapter(ProviderId providerId, String displayLabel,
                                  SessionMessagesLoader sessionMessagesLoader) {
        this(providerId, displayLabel, sessionMessagesLoader, null);
    }

    public CliOnlyProviderAdapter(ProviderId providerId, String displayLabel,
                                  SessionMessagesLoader sessionMessagesLoader,
                                  SessionHistoryLoader sessionHistoryLoader) {
        this.providerId = providerId;
        this.viewModel = new ProviderViewModel(providerId, displayLabel);
        this.sessionMessagesLoader = sessionMessagesLoader;
        this.sessionHistoryLoader = sessionHistoryLoader;
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
        // REASONING_THINKING 无条件声明:五家思考区均已落地(grok/kimi/pi native、omp/dsh marker),
        // 与 ProviderDescriptor.cliBuiltin 描述符层保持同集,消除两套 SSOT 漂移。
        // HISTORY 按历史 loader 有无(kimi/grok/pi/omp/dsh 有;dsh 走 host RPC 亦有)。
        return sessionMessagesLoader != null
                ? Set.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                         ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY)
                : Set.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                         ProviderCapability.REASONING_THINKING);
    }

    @Override
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        if (sessionMessagesLoader == null) {
            throw new UnsupportedOperationException("getSessionMessages is not supported by " + providerId.value());
        }
        return sessionMessagesLoader.apply(sessionId, cwd);
    }

    @Override
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        // 注入分页 loader 时初始页带 pageInfo(前端据此展示「加载更早」入口);
        // 未注入保持接口默认(全量 + pageInfo=null),omp/dsh 现状即此形态。
        if (sessionHistoryLoader != null) {
            return sessionHistoryLoader.apply(sessionId, cwd);
        }
        return ProviderAdapter.super.getInitialSessionHistory(sessionId, cwd);
    }
}
