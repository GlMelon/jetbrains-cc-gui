package com.github.claudecodegui.cli.claude;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClaudeCliModelResolverTest {

    @Test
    public void shouldUseFamilyMappingBeforeMainModelFallback() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "mimo-v2.5-pro");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "mapped-sonnet");

        String resolved = ClaudeCliModelResolver.resolveMapped("claude-role-sonnet", env);

        assertEquals("mapped-sonnet", resolved);
    }

    @Test
    public void shouldUseActualModelBeforeEnvironmentMappings() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "mimo-v2.5");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "ignored-sonnet");

        String resolved = ClaudeCliModelResolver.resolveMapped("glm5.2", env);

        assertEquals("glm5.2", resolved);
    }

    @Test
    public void shouldPreserveRequestLongContextSuffixForActualModel() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "ignored-global");

        String resolved = ClaudeCliModelResolver.resolveMapped("glm5.2[1m]", env);

        assertEquals("glm5.2[1m]", resolved);
    }

    @Test
    public void shouldUseSonnetMappingForClaudeSonnetModel() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "mimo-v2.5-pro");

        String resolved = ClaudeCliModelResolver.resolveMapped("claude-role-sonnet", env);

        assertEquals("mimo-v2.5-pro", resolved);
    }

    @Test
    public void shouldPreserveLongContextSuffixAfterResolvingFamilyMapping() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_OPUS_MODEL", "mimo-opus-pro");

        String resolved = ClaudeCliModelResolver.resolveMapped("claude-role-opus[1m]", env);

        assertEquals("mimo-opus-pro[1m]", resolved);
    }

    @Test
    public void shouldPreserveAlreadyCustomModelIds() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "mimo-v2.5-pro");

        String resolved = ClaudeCliModelResolver.resolveMapped("mimo-v2.5-pro", env);

        assertEquals("mimo-v2.5-pro", resolved);
    }

    @Test
    public void shouldUseSmallFastModelForHaikuBeforeDefaultHaikuMapping() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_SMALL_FAST_MODEL", "mimo-fast");
        env.addProperty("ANTHROPIC_DEFAULT_HAIKU_MODEL", "ignored-haiku");

        String resolved = ClaudeCliModelResolver.resolveMapped("claude-role-haiku", env);

        assertEquals("mimo-fast", resolved);
    }

    @Test
    public void shouldDisableOptionalCapabilitiesForMappedThirdPartyModelsByDefault() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "mimo-v2.5-pro");

        ClaudeCliModelResolver.ResolvedModel resolved = ClaudeCliModelResolver.resolveProfile(
                "claude-role-sonnet", env);

        assertEquals("mimo-v2.5-pro", resolved.model());
        assertFalse(resolved.capabilities().supportsEffort());
    }

    @Test
    public void shouldEnableEffortForCanonicalClaudeModels() {
        ClaudeCliModelResolver.ResolvedModel resolved = ClaudeCliModelResolver.resolveProfile(
                "claude-role-sonnet", new JsonObject());

        assertEquals("claude-role-sonnet", resolved.model());
        assertTrue(resolved.capabilities().supportsEffort());
    }

    @Test
    public void shouldAllowExplicitEffortCapabilityOverrideForCustomModels() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "mimo-v2.5-pro");
        env.addProperty("ANTHROPIC_MODEL_CAPABILITIES", "effort,tools");

        ClaudeCliModelResolver.ResolvedModel resolved = ClaudeCliModelResolver.resolveProfile(
                "claude-role-sonnet", env);

        assertEquals("mimo-v2.5-pro", resolved.model());
        assertTrue(resolved.capabilities().supportsEffort());
    }

    @Test
    public void shouldDisableOptionalCliCapabilitiesWhenExplicitlyConfigured() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "claude-compatible-proxy");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL_CAPABILITIES",
                "no-effort,no-mcp,no-add-dir,no-partial-messages");

        ClaudeCliModelResolver.ResolvedModel resolved = ClaudeCliModelResolver.resolveProfile(
                "claude-role-sonnet", env);

        assertFalse(resolved.capabilities().supportsEffort());
        assertFalse(resolved.capabilities().supportsMcp());
        assertFalse(resolved.capabilities().supportsAddDir());
        assertFalse(resolved.capabilities().supportsPartialMessages());
    }

    // ── Fable 角色(修复回归:resolver 私有 family 检测曾漏 fable,导致切 fable 仍用 main model) ──

    @Test
    public void shouldMapFableRoleToFableModelEnv() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "glm-5.3[1M]");
        env.addProperty("ANTHROPIC_DEFAULT_FABLE_MODEL", "glm-5.2[1M]");

        assertEquals("glm-5.2[1M]", ClaudeCliModelResolver.resolveMapped("claude-role-fable", env));
    }

    @Test
    public void shouldFallBackToOpusModelWhenFableEnvMissing() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "glm-5.3[1M]");
        env.addProperty("ANTHROPIC_DEFAULT_OPUS_MODEL", "glm-5.3-opus");

        // FABLE 未配时回退 Opus 通道(对齐 ClaudeRole.FABLE.envKeys fallback 顺序)
        assertEquals("glm-5.3-opus", ClaudeCliModelResolver.resolveMapped("claude-role-fable", env));
    }

    @Test
    public void shouldPreserveLongContextSuffixWhenMappingFable() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_FABLE_MODEL", "glm-5.2");

        assertEquals("glm-5.2[1m]", ClaudeCliModelResolver.resolveMapped("claude-role-fable[1m]", env));
    }
}
