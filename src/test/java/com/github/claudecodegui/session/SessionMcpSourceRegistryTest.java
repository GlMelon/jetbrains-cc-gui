package com.github.claudecodegui.session;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SessionMcpSourceRegistry:provider 路由(gateway 三 provider / kimi wire / 未注册降级 null)、
 * 重复注册 fail-fast;另以源码字符串检查兜底 SessionCapabilityService 的收口(总则六:
 * build() 的 MCP 段 Platform 耦合,无法纯单测)。
 */
public class SessionMcpSourceRegistryTest {

    @Test
    public void routesGatewayProvidersToGatewaySource() {
        for (ProviderType provider : new ProviderType[]{
                ProviderType.CLAUDE, ProviderType.CODEX, ProviderType.OPENCODE}) {
            assertTrue(provider.value(),
                    SessionMcpSourceRegistry.forProvider(provider.value()) instanceof GatewaySessionMcpSource);
        }
    }

    @Test
    public void routesKimiToWireSource() {
        assertTrue(SessionMcpSourceRegistry.forProvider(ProviderType.KIMI.value())
                instanceof KimiWireSessionMcpSource);
    }

    @Test
    public void unregisteredOrBlankProviderYieldsNull() {
        for (ProviderType provider : new ProviderType[]{
                ProviderType.GROK, ProviderType.PI, ProviderType.OMP, ProviderType.DSH}) {
            assertNull(provider.value(), SessionMcpSourceRegistry.forProvider(provider.value()));
        }
        assertNull(SessionMcpSourceRegistry.forProvider(null));
        assertNull(SessionMcpSourceRegistry.forProvider("  "));
        assertNull(SessionMcpSourceRegistry.forProvider("unknown-provider"));
    }

    @Test
    public void duplicateRegistrationFailsFast() {
        SessionMcpSourceRegistry registry = new SessionMcpSourceRegistry();
        registry.register(new KimiWireSessionMcpSource());
        try {
            registry.register(new KimiWireSessionMcpSource());
            fail("duplicate registration must throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(ProviderType.KIMI.name()));
        }
    }

    @Test
    public void capabilityServiceDelegatesMcpSectionToRegistry() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/session/SessionCapabilityService.java"));
        // MCP 段收口注册表路由,禁止 provider if/else 分支回落(总则五)。
        assertTrue(source.contains("SessionMcpSourceRegistry.forProvider"));
        assertFalse(source.contains("providerSupportsMcp"));
        assertFalse(source.contains("McpGatewayService"));
    }
}
