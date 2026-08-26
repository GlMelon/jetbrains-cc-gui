package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.model.selection.ModelSelectionResult;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.provider.CustomModelContextWindowProvider;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Regression tests for Claude model resolution in {@link ModelProviderHandler}.
 */
public class ModelProviderHandlerTest {

    @Test
    public void modelSelectionPayloadIncludesBackendResolvedContextDetails() {
        ModelSelectionResult selection = new ModelSelectionResult(
                "claude",
                "claude-role-sonnet",
                "claude-role-sonnet[1m]",
                "mimo-v2.5-pro",
                1_000_000,
                1_000_000,
                true
        );

        JsonObject payload = ModelProviderHandler.buildModelSelectionPayload(selection);

        assertEquals("model.selection", DownstreamEvent.MODEL_SELECTION.value());
        assertEquals("claude", payload.get("provider").getAsString());
        assertEquals("claude-role-sonnet", payload.get("selectedModel").getAsString());
        assertEquals("claude-role-sonnet[1m]", payload.get("storedModel").getAsString());
        assertEquals("mimo-v2.5-pro", payload.get("resolvedActualModel").getAsString());
        assertEquals(1_000_000, payload.get("effectiveContextWindow").getAsInt());
        assertEquals(1_000_000, payload.get("maxTokens").getAsInt());
        assertTrue(payload.get("supportsLongContext").getAsBoolean());
    }

    @Test
    public void hasModelChangedDetectsActualChange() {
        assertTrue(ModelProviderHandler.hasModelChanged("claude-role-sonnet", "claude-role-opus"));
    }

    @Test
    public void hasModelChangedIsFalseForSameModel() {
        assertFalse(ModelProviderHandler.hasModelChanged("claude-role-sonnet", "claude-role-sonnet"));
    }

    @Test
    public void hasModelChangedIsTrueWhenNoPreviousModel() {
        assertTrue(ModelProviderHandler.hasModelChanged(null, "claude-role-sonnet"));
    }

    @Test
    public void shouldPreferMainModelOverrideForAllClaudeModelFamilies() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "glm-4.7");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "ignored-sonnet");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-role-opus", env);

        assertEquals("glm-4.7", resolved);
    }

    @Test
    public void shouldUseFamilySpecificMappingForSelectedClaudeModel() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_HAIKU_MODEL", "haiku-proxy");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-role-haiku", env);

        assertEquals("haiku-proxy", resolved);
    }

    @Test
    public void shouldIgnoreSmallFastModelForHaikuResolution() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_SMALL_FAST_MODEL", "legacy-haiku-proxy");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-role-haiku", env);

        assertEquals("claude-role-haiku", resolved);
    }

    @Test
    public void shouldNotApplySonnetMappingToAlreadyCustomModelIds() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "glm-4.7");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("deepseek-v3", env);

        assertEquals("deepseek-v3", resolved);
    }

    @Test
    public void shouldUseResolvedModelForContextLimitWhenCapacitySuffixExists() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "glm-4.7[1M]");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-role-sonnet", env);

        assertEquals("glm-4.7[1M]", resolved);
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit(resolved));
    }

    @Test
    public void shouldNotHardCodeContextLimitsForConcreteCodexModels() {
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("gpt-provider-model"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("gpt-provider-model[1m]"));
    }

    @Test
    public void shouldReturnCorrectContextLimitsForClaudeRoleModels() {
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-role-sonnet"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-role-opus"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-role-fable"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-role-haiku"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("claude-role-sonnet[1m]"));
    }

    @Test
    public void shouldParseCapacitySuffixForCustomContextLimits() {
        assertEquals(500_000, ModelProviderHandler.getModelContextLimit("custom-model[500k]"));
        assertEquals(2_000_000, ModelProviderHandler.getModelContextLimit("custom-model[2m]"));
        assertEquals(100_000, ModelProviderHandler.getModelContextLimit("custom-model[100K]"));
    }

    @Test
    public void shouldDefaultTo200KForUnknownModels() {
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("unknown-model"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("qwen3-max"));
    }

    @Test
    public void shouldParseContextWindowFromSuffixForUnknownModels() {
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("deepseek-v4-pro[1m]"));
        assertEquals(128_000, ModelProviderHandler.getModelContextLimit("custom-model[128k]"));
    }

    @Test
    public void shouldPreferConfiguredCustomContextAndKeepExistingFallbacks() throws Exception {
        Path config = Files.createTempFile("model-context-limit", ".json");
        Files.writeString(config, """
                {
                  "customModelContextWindows": {
                    "codex": {
                      "custom-model": 750000
                    }
                  }
                }
                """);
        CustomModelContextWindowProvider.setInstanceForTests(
                CustomModelContextWindowProvider.createForTests(config)
        );

        try {
            assertEquals(750_000, ModelProviderHandler.getModelContextLimit("codex", "custom-model"));
            assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("codex", "custom-model[1m]"));
            assertEquals(500_000, ModelProviderHandler.getModelContextLimit("codex", "legacy-model[500k]"));
            assertEquals(200_000, ModelProviderHandler.getModelContextLimit("codex", "unknown-model"));
        } finally {
            CustomModelContextWindowProvider.setInstanceForTests(null);
        }
    }

    @Test
    public void shouldIgnoreConfiguredCustomContextForClaude() throws Exception {
        Path config = Files.createTempFile("model-context-limit", ".json");
        Files.writeString(config, """
                {
                  "customModelContextWindows": {
                    "claude": {
                      "custom-claude": 750000
                    }
                  }
                }
                """);
        CustomModelContextWindowProvider.setInstanceForTests(
                CustomModelContextWindowProvider.createForTests(config)
        );

        try {
            assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude", "custom-claude"));
            assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("claude", "custom-claude[1m]"));
        } finally {
            CustomModelContextWindowProvider.setInstanceForTests(null);
        }
    }
}
