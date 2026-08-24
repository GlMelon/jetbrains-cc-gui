package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

public class ClaudeProviderAdapter implements ProviderAdapter {
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.CLAUDE, "Claude");

    /**
     * 懒加载历史服务:其构造会拉起 {@link com.github.claudecodegui.bridge.NodeService}(依赖 IntelliJ platform),
     * 延迟到首次历史读取才初始化,使本 adapter 构造保持轻量、可在无 platform 的单元测试中实例化。
     */
    private volatile ClaudeHistoryService historyService;

    public ClaudeProviderAdapter() {
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.CLAUDE;
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

    /**
     * 历史加载入口分页化(对齐 CodexProviderAdapter):LOAD_SESSION 经
     * {@code SessionMessageOrchestrator#loadFromServer} 只加载最近一页 + pageInfo 下行,
     * 更早轮由前端按需 prepend。getSessionMessages 保持全量语义不动(存在独立调用面)。
     */
    @Override
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        return historyService().getInitialSessionHistory(sessionId, cwd);
    }

    private ClaudeHistoryService historyService() {
        ClaudeHistoryService local = historyService;
        if (local == null) {
            synchronized (this) {
                local = historyService;
                if (local == null) {
                    local = new ClaudeHistoryService();
                    historyService = local;
                }
            }
        }
        return local;
    }
}
