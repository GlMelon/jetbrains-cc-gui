package com.github.claudecodegui.session;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link SessionMcpSource} 注册表(Map 查表路由,总则五,对齐 FrontendActionDispatcher /
 * SessionRuntimeRegistry 范式)。新增 provider 的 MCP 观测方式只需新增实现 + 一行注册,
 * {@link SessionCapabilityService} 分派主体不变。
 */
public final class SessionMcpSourceRegistry {

    private static final SessionMcpSourceRegistry DEFAULT = createDefault();

    private final Map<ProviderType, SessionMcpSource> sources = new EnumMap<>(ProviderType.class);

    private static SessionMcpSourceRegistry createDefault() {
        SessionMcpSourceRegistry registry = new SessionMcpSourceRegistry();
        // claude/codex/opencode:MCP 统一经 melon gateway 注入,读 gateway 状态即实际加载集。
        registry.register(new GatewaySessionMcpSource(ProviderType.CLAUDE));
        registry.register(new GatewaySessionMcpSource(ProviderType.CODEX));
        registry.register(new GatewaySessionMcpSource(ProviderType.OPENCODE));
        // kimi:MCP 经 ACP session/new 注入 + CLI 自读 ~/.kimi-code/mcp.json,
        // 实际加载证据落盘在会话 wire 的 mcp.tools_discovered 事件。
        registry.register(new KimiWireSessionMcpSource());
        return registry;
    }

    /** 重复注册同一 provider 即装配错误,fail-fast。 */
    public void register(SessionMcpSource source) {
        if (sources.putIfAbsent(source.provider(), source) != null) {
            throw new IllegalArgumentException(
                    "Duplicate SessionMcpSource registered: " + source.provider());
        }
    }

    public SessionMcpSource find(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return ProviderType.fromValue(provider.trim().toLowerCase(Locale.ROOT))
                .map(sources::get)
                .orElse(null);
    }

    /**
     * 按 provider 解析注册的 source;未注册(grok/pi/omp/dsh)返回 null,
     * 调用方按「面板不可用」降级,不炸。
     */
    public static SessionMcpSource forProvider(String provider) {
        return DEFAULT.find(provider);
    }
}
