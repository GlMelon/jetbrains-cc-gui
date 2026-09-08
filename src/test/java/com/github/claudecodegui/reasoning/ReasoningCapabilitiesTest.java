package com.github.claudecodegui.reasoning;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link ReasoningCapabilities#levelsFor} 数据表行为锁定:provider 默认集、
 * codex 前缀规则、grok 模型差异、容量后缀剥离、null/未知模型回退、无能力 provider。
 */
public class ReasoningCapabilitiesTest {

    @Test
    public void providerDefaults() {
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), null));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), null));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.levelsFor(ProviderType.KIMI.value(), null));
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.levelsFor(ProviderType.PI.value(), null));
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.levelsFor(ProviderType.OMP.value(), null));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.OPENCODE.value(), null));
    }

    @Test
    public void codexModelPrefixRules() {
        // gpt-5.1 → [none, low, medium, high](含 -codex-mini 等尾缀的变体同前缀命中)
        assertEquals(List.of("none", "low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "gpt-5.1"));
        assertEquals(List.of("none", "low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "gpt-5.1-codex-mini"));
        // gpt-5.2 → [low, medium, high, xhigh]
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "gpt-5.2"));
        // gpt-5(无后缀)→ [minimal, low, medium, high];最长前缀优先,不误中 gpt-5.1/gpt-5.2
        assertEquals(List.of("minimal", "low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "gpt-5"));
        // 未知 codex 模型 → provider 默认集
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "gpt-6"));
    }

    @Test
    public void grokModelRules() {
        assertEquals(List.of("low", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "grok-3-mini"));
        assertEquals(List.of("none", "low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "grok-4.3"));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "grok-4.6"));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "grok-5"));
        // grok-4.5 无规则 → provider 默认集
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "grok-4.5"));
    }

    @Test
    public void capacitySuffixStrippedBeforeMatching() {
        assertEquals(List.of("minimal", "low", "medium", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "GPT-5 [200k]"));
        assertEquals(List.of("low", "high"),
                ReasoningCapabilities.levelsFor(ProviderType.GROK.value(), "Grok-3-Mini [1m]"));
    }

    @Test
    public void nullUnknownOrBlankModelFallsBackToProviderDefault() {
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), null));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), ""));
        assertEquals(List.of("low", "medium", "high", "xhigh"),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), "unknown-model"));
    }

    @Test
    public void nonReasoningProvidersReturnNull() {
        assertNull(ReasoningCapabilities.levelsFor(ProviderType.DSH.value(), null));
        assertNull(ReasoningCapabilities.levelsFor(ProviderType.DSH.value(), "deepseek-v3"));
        assertNull(ReasoningCapabilities.levelsFor(ProviderType.MINIMAX.value(), null));
        // claude 不在本表(role 派生,见 ClaudeRole.reasoningLevels)
        assertNull(ReasoningCapabilities.levelsFor(ProviderType.CLAUDE.value(), "claude-opus-4-5"));
        assertNull(ReasoningCapabilities.levelsFor(null, "gpt-5"));
    }

    @Test
    public void providerDefaultsExposesOnlyCapableProviders() {
        var defaults = ReasoningCapabilities.providerDefaults();
        assertEquals(List.of("low", "medium", "high", "xhigh"), defaults.get(ProviderType.CODEX.value()));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), defaults.get(ProviderType.KIMI.value()));
        assertEquals(6, defaults.size());
        assertNull(defaults.get(ProviderType.CLAUDE.value()));
        assertNull(defaults.get(ProviderType.DSH.value()));
        assertNull(defaults.get(ProviderType.MINIMAX.value()));
    }
}
