package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
import com.github.claudecodegui.provider.opencode.OpenCodeProviderAdapter;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Centralizes provider-specific bridge routing for session operations.
 * <p>
 * <b>装配 vs 路由(E7 决策·接受并标注)</b>:路由主体({@link #getSessionMessages} /
 * {@link #getInitialSessionHistory} 等)经 {@link ProviderRegistry#require} Map 查表(E3),
 * 新增 provider adapter <b>不需改主体</b>(总则五·开闭已满足),仅装配构造函数加一行 {@code new}。
 * 装配层手工 {@code new ...Adapter} 是无 DI 环境的装配惯例;2 个 adapter 经 {@code List.of}
 * 装进 {@link ProviderRegistry} 容器(容器本身已是注册化结构,新增 adapter 加一行即装配)。
 * 故评估接受手工装配并标注(E7),非待修复。
 */
public class SessionProviderRouter {

private final ProviderRegistry providerRegistry;

    public SessionProviderRouter() {
        this(new ProviderRegistry(buildAdapterList()));
    }

    public SessionProviderRouter(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    private static List<ProviderAdapter> buildAdapterList() {
        return List.of(
                new ClaudeProviderAdapter(),
                new CodexProviderAdapter(),
                new OpenCodeProviderAdapter()
        );
    }

    public void cleanupProviderSession(String provider, String sessionId, String cwd) {
        adapter(provider).cleanupProviderSession(sessionId, cwd);
    }

    public List<JsonObject> getSessionMessages(String provider, String sessionId, String cwd) {
        return adapter(provider).getSessionMessages(sessionId, cwd);
    }

    public SessionHistoryLoadResult getInitialSessionHistory(String provider, String sessionId, String cwd) {
        return adapter(provider).getInitialSessionHistory(sessionId, cwd);
    }

    private ProviderAdapter adapter(String provider) {
        // 直接用 ProviderId.of(内部 trim/lowercase 归一)路由,消除手写 provider 解析(总则五·开闭 / E3)。
        // 未知 provider 由 ProviderRegistry.require fail-fast 抛异常,对齐 registry 设计意图,
        // 取代原先「未知静默 fallback 到 CLAUDE」的偏离行为。
        return providerRegistry.require(ProviderId.of(provider));
    }
}

