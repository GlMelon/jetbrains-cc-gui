package com.github.claudecodegui.session.runtime;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * F1 六路径契约测试:验证三 Provider(Claude/Codex/OpenCode)× SDK/CLI 共六条
 * {@link SessionRuntime} 路径的路由键正确性、{@link SessionRuntimeRegistry} 注册完整性
 * 与 fail-fast 行为。
 *
 * <p>对应 {@code comprehensive-optimization-directions.md} §11.1 六路径矩阵与 §5 F1。
 * 六个实现类的 {@code provider()}/{@code runtimeType()} 不依赖构造参数,故 SDK runtime
 * 传 {@code null} bridge、CLI runtime 传 {@code null} cliManager 即可验证路由键,不触发
 * 真实 send 路径(避免 SDK bridge / Project 重依赖)。
 */
public class ProviderSixPathContractTest {

    /** 六个 runtime 实现(仅用于路由键与 Registry 契约,不触发 send)。 */
    private List<SessionRuntime> sixRuntimes() {
        return List.of(
                new ClaudeSdkSessionRuntime(null),
                new ClaudeCliSessionRuntime(null),
                new CodexSdkSessionRuntime(null),
                new CodexCliSessionRuntime(null),
                new OpenCodeSdkSessionRuntime(null),
                new OpenCodeCliSessionRuntime(null)
        );
    }

    @Test
    public void claudeSdkRuntimeHasClaudeSdkKey() {
        SessionRuntime rt = new ClaudeSdkSessionRuntime(null);
        assertEquals(ProviderType.CLAUDE, rt.provider());
        assertEquals(RuntimeType.SDK, rt.runtimeType());
        assertTrue(rt.supports(ProviderType.CLAUDE, RuntimeType.SDK));
    }

    @Test
    public void claudeCliRuntimeHasClaudeCliKey() {
        SessionRuntime rt = new ClaudeCliSessionRuntime(null);
        assertEquals(ProviderType.CLAUDE, rt.provider());
        assertEquals(RuntimeType.CLI, rt.runtimeType());
    }

    @Test
    public void codexSdkRuntimeHasCodexSdkKey() {
        SessionRuntime rt = new CodexSdkSessionRuntime(null);
        assertEquals(ProviderType.CODEX, rt.provider());
        assertEquals(RuntimeType.SDK, rt.runtimeType());
    }

    @Test
    public void codexCliRuntimeHasCodexCliKey() {
        SessionRuntime rt = new CodexCliSessionRuntime(null);
        assertEquals(ProviderType.CODEX, rt.provider());
        assertEquals(RuntimeType.CLI, rt.runtimeType());
    }

    @Test
    public void openCodeSdkRuntimeHasOpenCodeSdkKey() {
        SessionRuntime rt = new OpenCodeSdkSessionRuntime(null);
        assertEquals(ProviderType.OPENCODE, rt.provider());
        assertEquals(RuntimeType.SDK, rt.runtimeType());
    }

    @Test
    public void openCodeCliRuntimeHasOpenCodeCliKey() {
        SessionRuntime rt = new OpenCodeCliSessionRuntime(null);
        assertEquals(ProviderType.OPENCODE, rt.provider());
        assertEquals(RuntimeType.CLI, rt.runtimeType());
    }

    @Test
    public void registryResolvesAllSixPathsToRegisteredInstances() {
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry();
        List<SessionRuntime> runtimes = sixRuntimes();
        runtimes.forEach(registry::register);

        for (SessionRuntime rt : runtimes) {
            assertSame(rt, registry.resolve(rt.provider(), rt.runtimeType()));
        }
    }

    @Test
    public void registryAllReturnsSixRegisteredRuntimes() {
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry();
        sixRuntimes().forEach(registry::register);
        assertEquals(6, registry.all().size());
    }

    @Test(expected = IllegalStateException.class)
    public void registryRejectsDuplicateRegistration() {
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry();
        registry.register(new ClaudeSdkSessionRuntime(null));
        // 重复 (CLAUDE, SDK) 路径,fail-fast
        registry.register(new ClaudeSdkSessionRuntime(null));
    }

    @Test(expected = IllegalStateException.class)
    public void registryThrowsForUnregisteredPath() {
        SessionRuntimeRegistry registry = new SessionRuntimeRegistry();
        // 空注册表,任何 resolve 都应 fail-fast
        registry.resolve(ProviderType.OPENCODE, RuntimeType.SDK);
    }

    @Test
    public void sixPathsCoverAllProviderRuntimeCombinations() {
        List<SessionRuntime> runtimes = sixRuntimes();
        assertEquals(6, runtimes.size());

        // 六路径必须正好覆盖 3 Provider × 2 Runtime 的全组合,无重复、无遗漏
        Set<String> keys = new HashSet<>();
        for (SessionRuntime rt : runtimes) {
            assertTrue("duplicate path " + rt.provider() + "/" + rt.runtimeType(),
                    keys.add(rt.provider().value() + "/" + rt.runtimeType()));
        }
        for (ProviderType provider : ProviderType.values()) {
            for (RuntimeType runtime : RuntimeType.values()) {
                assertTrue("missing path " + provider + "/" + runtime,
                        keys.contains(provider.value() + "/" + runtime));
            }
        }
    }
}
