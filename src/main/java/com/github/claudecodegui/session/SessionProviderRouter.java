package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.CliOnlyProviderAdapter;
import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
import com.github.claudecodegui.provider.common.NativeCliHistoryPageService;
import com.github.claudecodegui.provider.common.NativeCliHistoryReaders;
import com.github.claudecodegui.provider.dsh.DshHistoryReader;
import com.github.claudecodegui.provider.omp.OmpHistoryReader;
import com.github.claudecodegui.provider.opencode.OpenCodeProviderAdapter;
import com.google.gson.JsonObject;

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
        // Grok / Kimi / Pi / OMP / DSH:纯 CLI marker provider,通用轻量适配器;各家上游均有
        // 会话落盘(本地 *HistoryReader 或 host 侧 RPC),注入 SessionMessagesLoader 支撑
        // 历史面板「点会话回load」(SessionProviderRouter.getInitialSessionHistory → getSessionMessages)。
        // 未注入 loader 时 getSessionMessages 走接口默认 fail-fast。
        // grok/kimi/pi 额外注入分页 loader(NativeCliHistoryPageService,reader 定位单点在
        // NativeCliHistoryReaders,翻页 handler 共用):初始页只回最近 NATIVE_CLI_HISTORY_PAGE_SIZE 轮
        // + pageInfo,更早轮经 LOAD_CODEX_HISTORY_PAGE 前端按需 prepend;omp/dsh 暂不注入
        // (消息形状的轮边界判定未验证,handler 白名单不含,记 backlog)。
        OmpHistoryReader ompReader = new OmpHistoryReader();
        DshHistoryReader dshReader = new DshHistoryReader();
        CliOnlyProviderAdapter.SessionMessagesLoader grokLoader = NativeCliHistoryReaders.grok()::read;
        CliOnlyProviderAdapter.SessionMessagesLoader kimiLoader = NativeCliHistoryReaders.kimi()::read;
        CliOnlyProviderAdapter.SessionMessagesLoader piLoader = NativeCliHistoryReaders.pi()::read;
        NativeCliHistoryPageService grokPage = new NativeCliHistoryPageService(grokLoader::apply);
        NativeCliHistoryPageService kimiPage = new NativeCliHistoryPageService(kimiLoader::apply);
        NativeCliHistoryPageService piPage = new NativeCliHistoryPageService(piLoader::apply);
        return List.of(
                new ClaudeProviderAdapter(),
                new CodexProviderAdapter(),
                new OpenCodeProviderAdapter(),
                new CliOnlyProviderAdapter(ProviderId.GROK, "Grok", grokLoader, grokPage::loadInitialPage),
                new CliOnlyProviderAdapter(ProviderId.KIMI, "Kimi", kimiLoader, kimiPage::loadInitialPage),
                new CliOnlyProviderAdapter(ProviderId.PI, "Pi", piLoader, piPage::loadInitialPage),
                // OMP:本地 JSONL 落盘(OmpHistoryReader);DSH:host 侧历史经 bridge RPC 拉取
                new CliOnlyProviderAdapter(ProviderId.OMP, "OMP", (sessionId, cwd) -> {
                    try {
                        return ompReader.getSessionMessages(sessionId, cwd);
                    } catch (Exception e) {
                        return List.of();
                    }
                }),
                new CliOnlyProviderAdapter(ProviderId.DSH, "DeepSeek Harness", (sessionId, cwd) ->
                        dshReader.getSessionMessages(sessionId, cwd))
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

    /**
     * 该 provider 是否声明指定能力(F1 capability 查询)。未知 provider 返回 false——
     * 调用方(如 SessionCapabilityService 的 MCP 门禁)按「不支持」降级,不炸面板。
     */
    public boolean supports(String provider, ProviderCapability capability) {
        try {
            return adapter(provider).supports(capability);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ProviderAdapter adapter(String provider) {
        // 直接用 ProviderId.of(内部 trim/lowercase 归一)路由,消除手写 provider 解析(总则五·开闭 / E3)。
        // 未知 provider 由 ProviderRegistry.require fail-fast 抛异常,对齐 registry 设计意图,
        // 取代原先「未知静默 fallback 到 CLAUDE」的偏离行为。
        return providerRegistry.require(ProviderId.of(provider));
    }
}
