package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Set;

/**
 * OpenCode provider adapter: delegates history operations to {@link OpenCodeHistoryReader}
 * (Java 直读,支持 OpenCode 1.x SQLite {@code opencode.db} 与旧版 JSONL,无 Node spawn 依赖)。
 * <p>
 * 历史的 spawn 链路({@link OpenCodeHistoryService})保留作为 fallback 基础设施,但 adapter 主路径
 * 走 Reader:① 性能(无子进程启动开销);② 支持 1.x SQLite 新存储格式;③ 无 Node.js 依赖。
 */
public class OpenCodeProviderAdapter implements ProviderAdapter {
    private static final Logger LOG = Logger.getInstance(OpenCodeProviderAdapter.class);
    private static final ProviderViewModel VIEW_MODEL = new ProviderViewModel(ProviderId.OPENCODE, "OpenCode");

    /**
     * 懒加载历史读取器:延迟到首次历史读取才初始化,使本 adapter 构造保持轻量、
     * 可在无 platform 的单元测试中实例化(Reader 构造本身不触碰 NodeDetector/platform)。
     */
    private volatile OpenCodeHistoryReader historyReader;

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
        try {
            return historyReader().getSessionMessages(sessionId, cwd);
        } catch (Exception e) {
            LOG.warn("[OpenCode] history read failed for session " + sessionId + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    private OpenCodeHistoryReader historyReader() {
        OpenCodeHistoryReader local = historyReader;
        if (local == null) {
            synchronized (this) {
                local = historyReader;
                if (local == null) {
                    local = new OpenCodeHistoryReader();
                    historyReader = local;
                }
            }
        }
        return local;
    }
}

