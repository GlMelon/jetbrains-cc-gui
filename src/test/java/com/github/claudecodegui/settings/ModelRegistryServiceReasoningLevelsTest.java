package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ModelRegistryService.serialize 的 reasoning 下发行为锁定:
 * per-model supportedReasoningLevels(claude role 派生 + 其余 provider 数据表派生)
 * 与 payload root 的 providerDefaults 回退表。
 */
public class ModelRegistryServiceReasoningLevelsTest {

    private static JsonObject serializeItem(ModelConfig model) {
        JsonObject root = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(model)));
        return root.getAsJsonArray("items").get(0).getAsJsonObject();
    }

    private static List<String> toList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        for (var e : arr) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static ModelConfig model(String id, String provider, String role, String actualModel) {
        return new ModelConfig(id, provider, role, id, actualModel, "", 200_000, false, true);
    }

    @Test
    public void claudeStillDerivesFromRole() {
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                toList(serializeItem(model("claude-role-sonnet", CommonConstants.PROVIDER_CLAUDE,
                        "sonnet", "claude-sonnet-4-6"))
                        .getAsJsonArray("supportedReasoningLevels")));
        // HAIKU 3 档行为保持现状
        assertEquals(List.of("low", "medium", "high"),
                toList(serializeItem(model("claude-role-haiku", CommonConstants.PROVIDER_CLAUDE,
                        "haiku", "claude-haiku-4-5"))
                        .getAsJsonArray("supportedReasoningLevels")));
        // claude role 未知 → 不下发
        assertFalse(serializeItem(model("custom", CommonConstants.PROVIDER_CLAUDE, "", "mimo-v2.5"))
                .has("supportedReasoningLevels"));
    }

    @Test
    public void nonClaudeProvidersDeriveFromCapabilityTable() {
        // codex 模型规则命中(actualModel 携带 gpt-5.1)
        assertEquals(List.of("none", "low", "medium", "high"),
                toList(serializeItem(model("gpt-5.1", ProviderType.CODEX.value(), "", "gpt-5.1"))
                        .getAsJsonArray("supportedReasoningLevels")));
        // codex 未知模型 → provider 默认集
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                toList(serializeItem(model("future", ProviderType.CODEX.value(), "", "gpt-9"))
                        .getAsJsonArray("supportedReasoningLevels")));
        // kimi 默认集
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                toList(serializeItem(model("k2", ProviderType.KIMI.value(), "", "kimi-k2"))
                        .getAsJsonArray("supportedReasoningLevels")));
        // opencode 安全默认子集
        assertEquals(List.of("low", "medium", "high"),
                toList(serializeItem(model("oc", ProviderType.OPENCODE.value(), "", "any"))
                        .getAsJsonArray("supportedReasoningLevels")));
    }

    @Test
    public void actualModelPreferredOverId() {
        // id 命中 gpt-5 规则,但 actualModel 非空优先 → gpt-5.2 规则
        JsonObject item = serializeItem(model("gpt-5", ProviderType.CODEX.value(), "", "gpt-5.2-codex"));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                toList(item.getAsJsonArray("supportedReasoningLevels")));
        // actualModel 空 → 回退 id 匹配
        JsonObject byId = serializeItem(model("grok-3-mini", ProviderType.GROK.value(), "", ""));
        assertEquals(List.of("low", "high"),
                toList(byId.getAsJsonArray("supportedReasoningLevels")));
    }

    @Test
    public void nonReasoningProvidersOmitLevelsField() {
        assertFalse(serializeItem(model("d", ProviderType.DSH.value(), "", "deepseek-v3"))
                .has("supportedReasoningLevels"));
        assertFalse(serializeItem(model("m", ProviderType.MINIMAX.value(), "", "MiniMax-M2"))
                .has("supportedReasoningLevels"));
    }

    @Test
    public void rootCarriesProviderDefaults() {
        JsonObject root = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(
                model("d", ProviderType.DSH.value(), "", "deepseek-v3"))));
        assertTrue(root.has("providerDefaults"));
        JsonObject defaults = root.getAsJsonObject("providerDefaults");
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                toList(defaults.getAsJsonArray(ProviderType.CODEX.value())));
        assertEquals(List.of("low", "medium", "high"),
                toList(defaults.getAsJsonArray(ProviderType.GROK.value())));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                toList(defaults.getAsJsonArray(ProviderType.KIMI.value())));
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                toList(defaults.getAsJsonArray(ProviderType.PI.value())));
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                toList(defaults.getAsJsonArray(ProviderType.OMP.value())));
        assertEquals(List.of("low", "medium", "high"),
                toList(defaults.getAsJsonArray(ProviderType.OPENCODE.value())));
        // claude(role 派生)与无能力 provider 不下发
        assertNull(defaults.get(ProviderType.CLAUDE.value()));
        assertNull(defaults.get(ProviderType.DSH.value()));
        assertNull(defaults.get(ProviderType.MINIMAX.value()));
        assertEquals(6, defaults.keySet().size());
    }
}
