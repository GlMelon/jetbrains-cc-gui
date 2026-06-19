package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.model.selection.ModelSelectionResult;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.google.gson.JsonObject;
import org.junit.Test;

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

    // ============================================================================
    // Provider transition matrix — see L2 in NODE_PROCESS_LEAK_FIX_TASKS.md.
    // The Claude daemon must be torn down when (and ONLY when) the tab leaves
    // the Claude family. These tests pin the full matrix.
    // ============================================================================

    @Test
    public void shouldShutdownDaemonWhenSwitchingFromClaudeToCodex() {
        // The bug: switching to Codex previously left the Claude daemon alive,
        // causing it to accumulate as a phantom process across the tab lifetime.
        assertTrue(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("claude", "codex"));
    }

    @Test
    public void shouldNotShutdownDaemonWhenSwitchingFromCodexToClaude() {
        // Returning to Claude must NOT shut down the daemon — the next message
        // will lazily start a fresh one if needed.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("codex", "claude"));
    }

    @Test
    public void shouldNotShutdownDaemonOnClaudeToClaudeReaffirmation() {
        // useMessageSender re-fires set_provider("claude") on every message send.
        // We must never tear down the warm daemon on these no-op transitions.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("claude", "claude"));
    }

    @Test
    public void shouldNotShutdownDaemonOnCodexToCodexReaffirmation() {
        // Same protection on the Codex side — there's no Claude daemon to kill
        // here, but the predicate must still return false so we don't log noise.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("codex", "codex"));
    }

    @Test
    public void shouldNotShutdownDaemonOnNullPreviousProvider() {
        // Initial startup may surface a null previous provider; nothing to clean up yet.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch(null, "codex"));
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch(null, "claude"));
    }

    @Test
    public void shouldNotShutdownDaemonOnNullNewProvider() {
        // Defensive: a null new provider should not be treated as a leave-claude transition.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("claude", null));
    }

    @Test
    public void shouldShutdownDaemonWhenSwitchingFromClaudeToUnknownProvider() {
        // Future-proof: any non-claude target after Claude qualifies as leave-claude.
        assertTrue(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("claude", "gemini"));
    }

    @Test
    public void shouldNotShutdownDaemonOnEmptyNewProvider() {
        // Empty string is not a valid "leave claude" transition — it usually
        // signals an init race. The predicate must treat it the same as null
        // to avoid spurious 5–10s daemon restarts.
        assertFalse(ModelProviderHandler.shouldShutdownClaudeDaemonOnProviderSwitch("claude", ""));
    }
}
