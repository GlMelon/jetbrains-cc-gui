package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.CliOnlyProviderAdapter;
import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
import com.github.claudecodegui.provider.grok.GrokHistoryReader;
import com.github.claudecodegui.provider.kimi.KimiHistoryReader;
import com.github.claudecodegui.provider.opencode.OpenCodeProviderAdapter;
import com.github.claudecodegui.provider.pi.PiHistoryReader;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * Centralizes provider-specific bridge routing for session operations.
 * <p>
 * <b>装配 vs 路由(E7 决策·接受并标注)</b>:路由主体({@link #getSessionMessages} /
 * {@link #getInitialSessionHistory} 等)经 {@link ProviderRegistry#require} Map 查表(E3),
 * 新增 provider adapter <b>不需改主体</b>(总则五·开闭已满足),仅装配构造函数加一行 {@code new}。
 * 装配层手工 {@code new ...Adapter} 是无 DI 环境的装配惯例;8 个 adapter(3 全功能 + 5 纯 CLI)
 * 经 {@code List.of} 装进 {@link ProviderRegistry} 容器(容器本身已是注册化结构,新增 adapter 加一行即装配)。
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
        // Grok / Kimi / Pi:纯 CLI marker provider,通用轻量适配器;三家的上游 CLI 均有本地会话
        // 落盘(各自 *HistoryReader),注入 SessionMessagesLoader 支撑历史面板「点会话回load」
        // (SessionProviderRouter.getInitialSessionHistory → getSessionMessages)。
        // 未注入 loader 时 getSessionMessages 走接口默认 fail-fast。
        KimiHistoryReader kimiReader = new KimiHistoryReader();
        GrokHistoryReader grokReader = new GrokHistoryReader();
        PiHistoryReader piReader = new PiHistoryReader();
        return List.of(
                new ClaudeProviderAdapter(),
                new CodexProviderAdapter(),
                new OpenCodeProviderAdapter(),
                new CliOnlyProviderAdapter(ProviderId.GROK, "Grok", (sessionId, cwd) -> {
                    Path dir = grokReader.findSessionDir(sessionId, cwd);
                    return dir == null ? List.of() : grokReader.loadMessages(dir);
                }),
                new CliOnlyProviderAdapter(ProviderId.KIMI, "Kimi", (sessionId, cwd) -> {
                    Path dir = kimiReader.findSessionDir(sessionId, cwd);
                    return dir == null ? List.of() : kimiReader.loadMessages(dir);
                }),
                new CliOnlyProviderAdapter(ProviderId.PI, "Pi", (sessionId, cwd) -> {
                    Path file = piReader.findSessionFile(sessionId, cwd);
                    return file == null ? List.of() : piReader.loadMessages(file);
                }),
                // OMP / DSH:上游 v0.5.4 新增纯 CLI provider(omp=pi fork marker 模式;dsh=marker+host RPC),
                // 同样以轻量适配器注册;会话发送经 session/runtime 路由,此处仅占位 provider 身份
                // (上游无会话落盘体系,不注入历史读取器)。
                new CliOnlyProviderAdapter(ProviderId.OMP, "OMP"),
                new CliOnlyProviderAdapter(ProviderId.DSH, "DeepSeek Harness")
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
