package com.github.claudecodegui.startup;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProviderPrewarmRegistryTest {

    @Test
    public void defaultRegistryCoversEveryProviderExactlyOnce() {
        ProviderPrewarmRegistry registry = ProviderPrewarmRegistry.defaultRegistry();

        assertEquals(ProviderType.values().length, registry.strategies().size());
        for (ProviderType provider : ProviderType.values()) {
            ProviderPrewarmStrategy strategy = registry.strategy(provider);
            assertNotNull(strategy);
            assertSame(provider, strategy.provider());
            assertNotNull(strategy.policy());
            assertTrue(strategy.policy().timeout().compareTo(Duration.ZERO) > 0);
            assertNotNull(strategy.policy().fallback());
        }
    }

    @Test
    public void duplicateProviderRegistrationFailsFast() {
        ProviderPrewarmStrategy first = strategy(ProviderType.CLAUDE);
        ProviderPrewarmStrategy duplicate = strategy(ProviderType.CLAUDE);
        try {
            new ProviderPrewarmRegistry(List.of(first, duplicate));
            fail("expected duplicate provider registration to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate provider prewarm strategy"));
        }
    }

    @Test
    public void missingProviderRegistrationFailsFast() {
        try {
            new ProviderPrewarmRegistry(List.of(strategy(ProviderType.CLAUDE)));
            fail("expected missing provider registration to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Missing provider prewarm strategies"));
        }
    }

    @Test
    public void explicitArchitectureDifferencesAreEncodedInPolicies() {
        ProviderPrewarmRegistry registry = ProviderPrewarmRegistry.defaultRegistry();

        ProviderPrewarmPolicy claude = registry.strategy(ProviderType.CLAUDE).policy();
        assertFalse(claude.executableProbe());
        assertEquals(PrewarmFallback.RETRY_ON_FIRST_USE, claude.fallback());

        ProviderPrewarmPolicy omp = registry.strategy(ProviderType.OMP).policy();
        assertFalse(omp.executableProbe());
        assertTrue(omp.channelProbe());
        assertEquals(PrewarmFallback.DIRECT_CHANNEL, omp.fallback());

        ProviderPrewarmPolicy dsh = registry.strategy(ProviderType.DSH).policy();
        assertFalse(dsh.executableProbe());
        assertTrue(dsh.channelProbe());
        assertTrue(dsh.configurationLoad());
        assertEquals(PrewarmFallback.HOST_CHANNEL, dsh.fallback());
    }

    @Test
    public void resolverFailuresRemainRetryableInsteadOfBeingCached() throws Exception {
        String commonResolver = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/cli/common/ProviderCliResolver.java"),
                StandardCharsets.UTF_8);
        String codexResolver = Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/session/runtime/CodexCliResolver.java"),
                StandardCharsets.UTF_8);

        assertTrue(commonResolver.contains("if (result != null) {\n                CACHED_EXECUTABLES.put(type, result);\n                return result;"));
        assertTrue(commonResolver.contains("return type.cliCommandForPlatform();"));
        assertTrue(codexResolver.contains("if (result != null) {\n                cachedExecutable = result;\n                return result;"));
        assertTrue(codexResolver.contains("return ProviderType.CODEX.cliCommandForPlatform();"));
    }

    @Test
    public void resolverStrategyHonorsCancellationBeforeProbe() {
        AtomicBoolean invoked = new AtomicBoolean();
        ProviderPrewarmStrategy strategy = new ProviderPrewarmStrategy() {
            @Override
            public ProviderType provider() {
                return ProviderType.CLAUDE;
            }

            @Override
            public ProviderPrewarmPolicy policy() {
                return new ProviderPrewarmPolicy(true, true, false, false, false,
                        Duration.ofSeconds(1), PrewarmFallback.RETRY_ON_FIRST_USE);
            }

            @Override
            public void prewarm(java.util.function.BooleanSupplier cancelled) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                invoked.set(true);
            }
        };

        strategy.prewarm(() -> true);
        assertFalse(invoked.get());
    }

    private static ProviderPrewarmStrategy strategy(ProviderType provider) {
        return new ProviderPrewarmStrategy() {
            @Override
            public ProviderType provider() {
                return provider;
            }

            @Override
            public ProviderPrewarmPolicy policy() {
                return new ProviderPrewarmPolicy(false, false, false, false, false,
                        Duration.ofSeconds(1), PrewarmFallback.RETRY_ON_FIRST_USE);
            }

            @Override
            public void prewarm(java.util.function.BooleanSupplier cancelled) {
            }
        };
    }
}
