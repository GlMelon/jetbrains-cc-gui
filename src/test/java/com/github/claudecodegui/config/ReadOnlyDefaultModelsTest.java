package com.github.claudecodegui.config;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReadOnlyDefaultModelsTest {
    @Test
    public void computeReturnsFourReadOnlyRolesWithoutCodexWhenNoConfig() {
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(Map.of(), Map.of());

        assertEquals(4, defaults.size());
        for (ModelConfig model : defaults) {
            assertEquals(CommonConstants.PROVIDER_CLAUDE, model.provider());
            assertTrue(model.readOnly());
            assertTrue(model.enabled());
            assertEquals("", model.actualModel()); // 无 settings.json 配置
        }
        assertTrue(defaults.stream().anyMatch(m -> m.id().equals(ClaudeRole.SONNET.roleId())));
    }

    @Test
    public void computeResolvesClaudeActualModelFromEnv() {
        Map<String, String> claudeEnv = Map.of(
                CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL, "mimo-v2.5");
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(claudeEnv, Map.of());

        ModelConfig sonnet = defaults.stream()
                .filter(m -> m.id().equals(ClaudeRole.SONNET.roleId())).findFirst().orElseThrow();
        assertEquals("mimo-v2.5", sonnet.actualModel());
    }

    @Test
    public void computeIncludesCodexReadOnlyWhenModelPresent() {
        Map<String, String> codexEnv = Map.of(CliConstants.ENV_CODEX_MODEL, "gpt-5");
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(Map.of(), codexEnv);

        ModelConfig codex = defaults.stream()
                .filter(m -> CommonConstants.PROVIDER_CODEX.equals(m.provider())).findFirst().orElseThrow();
        assertEquals("gpt-5", codex.id());
        assertTrue(codex.readOnly());
        assertEquals(5, defaults.size()); // 4 roles + 1 codex
    }

    @Test
    public void mergeReservesRoleKeysReadOnlyAlwaysWins() {
        ModelConfig readOnlySonnet = new ModelConfig(ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE, "sonnet", "Sonnet", "", "",
                200_000, true, true, true);
        ModelConfig userSonnetOverride = new ModelConfig(ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE, "sonnet", "Hacked", "evil", "",
                200_000, true, true, false);
        ModelRegistryConfig userLayer = new ModelRegistryConfig(List.of(userSonnetOverride));

        ModelRegistryConfig merged = ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(
                userLayer, List.of(readOnlySonnet));

        // 用户层 claude-role-sonnet 被跳过,只读恒胜
        assertEquals(1, merged.models().size());
        assertEquals("", merged.models().get(0).actualModel());
        assertTrue(merged.models().get(0).readOnly());
    }

    @Test
    public void mergeCodexUserWinsAndCustomAppended() {
        ModelConfig readOnlyCodex = new ModelConfig("gpt-5", CommonConstants.PROVIDER_CODEX,
                "", "GPT-5", "", "", 200_000, false, true, true);
        ModelConfig userCodexSameKey = new ModelConfig("gpt-5[1m]", CommonConstants.PROVIDER_CODEX,
                "", "My GPT-5", "", "", 1_000_000, true, true, false);
        ModelConfig userCustom = new ModelConfig("mimo-v2.5", CommonConstants.PROVIDER_CLAUDE,
                "sonnet", "Mimo", "mimo-v2.5", "", 1_000_000, true, true, false);
        ModelRegistryConfig userLayer = new ModelRegistryConfig(List.of(userCodexSameKey, userCustom));

        ModelRegistryConfig merged = ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(
                userLayer, List.of(readOnlyCodex));

        // codex:用户优先(替换只读,可编辑);custom 原样追加
        ModelConfig codex = merged.models().stream()
                .filter(m -> CommonConstants.PROVIDER_CODEX.equals(m.provider())).findFirst().orElseThrow();
        assertFalse(codex.readOnly());
        assertEquals("My GPT-5", codex.label());
        assertTrue(merged.models().stream().anyMatch(m -> "mimo-v2.5".equals(m.id())));
    }

    @Test
    public void dedupKeyStripsCapacitySuffixAndLowercases() {
        assertEquals("codex:gpt-5", ReadOnlyDefaultModels.dedupKey("codex", "GPT-5[1m]"));
        assertEquals("claude:claude-role-sonnet",
                ReadOnlyDefaultModels.dedupKey("claude", "claude-role-sonnet"));
    }
}
