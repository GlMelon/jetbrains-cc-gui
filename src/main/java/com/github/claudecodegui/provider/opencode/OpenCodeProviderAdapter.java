package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * OpenCode provider adapter: delegates history operations to {@link OpenCodeHistoryService}.
 */
public class OpenCodeProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.OPENCODE, "OpenCode");

    /**
     * 懒加载历史服务:其构造会拉起 {@link com.github.claudecodegui.bridge.NodeService}(依赖 IntelliJ platform),
     * 延迟到首次历史读取才初始化,使本 adapter 构造保持轻量、可在无 platform 的单元测试中实例化。
     */
    private volatile OpenCodeHistoryService historyService;

    public OpenCodeProviderAdapter() {
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
        return historyService().getSessionMessages(sessionId, cwd);
    }

    private OpenCodeHistoryService historyService() {
        OpenCodeHistoryService local = historyService;
        if (local == null) {
            synchronized (this) {
                local = historyService;
                if (local == null) {
                    local = new OpenCodeHistoryService();
                    historyService = local;
                }
            }
        }
        return local;
    }
}