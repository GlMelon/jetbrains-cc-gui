package com.github.claudecodegui.model.selection;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultModelCapabilityResolverTest {

    @Test
    public void claudeFamilyModelStoresOneMillionSuffixWhenLongContextIsRequested() {
        DefaultModelCapabilityResolver resolver =
                new DefaultModelCapabilityResolver(ModelRegistryConfig.getDefault());

        ModelSelectionResult result = resolver.resolve(new ModelSelectionRequest(
                CommonConstants.PROVIDER_CLAUDE,
                "claude-role-sonnet",
                1_000_000,
                true
        ));

        assertEquals("claude-role-sonnet[1m]", result.storedModel());
        assertEquals("claude-role-sonnet", result.resolvedActualModel());
        assertEquals(1_000_000, result.effectiveContextWindow());
        assertEquals(1_000_000, result.maxTokens());
        assertTrue(result.supportsLongContext());
    }

    @Test
    public void nonClaudeModelDoesNotStoreOneMillionSuffix() {
        ModelRegistryConfig registry = new ModelRegistryConfig(List.of(new ModelConfig(
                "deepseek-v4-pro",
                CommonConstants.PROVIDER_CLAUDE,
                "",
                "DeepSeek",
                "deepseek-v4-pro",
                "",
                1_000_000,
                true,
                true
        )));
        DefaultModelCapabilityResolver resolver = new DefaultModelCapabilityResolver(registry);

        ModelSelectionResult result = resolver.resolve(new ModelSelectionRequest(
                CommonConstants.PROVIDER_CLAUDE,
                "deepseek-v4-pro",
                1_000_000,
                true
        ));

        assertEquals("deepseek-v4-pro", result.storedModel());
        assertEquals("deepseek-v4-pro", result.resolvedActualModel());
        assertEquals(1_000_000, result.maxTokens());
        assertTrue(result.supportsLongContext());
    }

    @Test
    public void registryContextWindowCapsRequestedMaxTokens() {
        ModelRegistryConfig registry = new ModelRegistryConfig(List.of(new ModelConfig(
                "gpt-fast",
                CommonConstants.PROVIDER_CODEX,
                "",
                "GPT Fast",
                "gpt-fast",
                "",
                128_000,
                false,
                true
        )));
        DefaultModelCapabilityResolver resolver = new DefaultModelCapabilityResolver(registry);

        ModelSelectionResult result = resolver.resolve(new ModelSelectionRequest(
                CommonConstants.PROVIDER_CODEX,
                "gpt-fast",
                200_000,
                false
        ));

        assertEquals(200_000, result.effectiveContextWindow());
        assertEquals(128_000, result.maxTokens());
        assertFalse(result.supportsLongContext());
    }

    @Test
    public void missingRequestedContextFallsBackToRegistryOrDefaultLimit() {
        ModelRegistryConfig registry = new ModelRegistryConfig(List.of(new ModelConfig(
                "compact-model",
                CommonConstants.PROVIDER_CODEX,
                "",
                "Compact",
                "compact-model",
                "",
                64_000,
                false,
                true
        )));
        DefaultModelCapabilityResolver resolver = new DefaultModelCapabilityResolver(registry);

        ModelSelectionResult configured = resolver.resolve(new ModelSelectionRequest(
                CommonConstants.PROVIDER_CODEX,
                "compact-model",
                null,
                false
        ));
        ModelSelectionResult unknown = resolver.resolve(new ModelSelectionRequest(
                CommonConstants.PROVIDER_CODEX,
                "unknown-model",
                null,
                false
        ));

        assertEquals(64_000, configured.effectiveContextWindow());
        assertEquals(64_000, configured.maxTokens());
        assertEquals(200_000, unknown.effectiveContextWindow());
        assertEquals(200_000, unknown.maxTokens());
    }
}
