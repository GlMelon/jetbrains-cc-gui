package com.github.claudecodegui.cli.codex;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * codex reasoningEffort 发送前钳制验证(HTTP 400 fail-fast,必须本地拦):
 * 支持集按 ReasoningCapabilities (codex, model) 派生——gpt-5.1 无 xhigh、
 * gpt-5.2 含 xhigh、gpt-5 仅 minimal..high、未知模型默认 [low..xhigh]
 * (官方调研 2026-09-07;clamp 规则:取不超过请求值的最高支持档)。
 */
public class CodexReasoningEffortClampTest {

    @Test
    public void passesThroughWhenSupported() {
        assertEquals("high", CodexCliSession.clampReasoningEffort("high", "gpt-5.2"));
        assertEquals("xhigh", CodexCliSession.clampReasoningEffort("xhigh", "gpt-5.2"));
        assertEquals("minimal", CodexCliSession.clampReasoningEffort("minimal", "gpt-5"));
        assertEquals("none", CodexCliSession.clampReasoningEffort("none", "gpt-5.1"));
        assertEquals("high", CodexCliSession.clampReasoningEffort("high", null));
    }

    @Test
    public void clampsXhighDownOnModelsWithoutIt() {
        // gpt-5.1 档位 [none,low,medium,high]:xhigh → high(否则 HTTP 400)
        assertEquals("high", CodexCliSession.clampReasoningEffort("xhigh", "gpt-5.1"));
        // gpt-5 档位 [minimal..high]:xhigh/max → high
        assertEquals("high", CodexCliSession.clampReasoningEffort("max", "gpt-5"));
        // 未知模型默认 [low..xhigh]:max → xhigh
        assertEquals("xhigh", CodexCliSession.clampReasoningEffort("max", "gpt-unknown"));
    }

    @Test
    public void clampsUpOnlyWhenNoLowerSupported() {
        // gpt-5.1 无 minimal/none 之下的更低语义档;none 请求在 [none,...] 内直发,
        // 在无 none 的集合(默认集)→ 无更低支持档 → 取最低 low
        assertEquals("low", CodexCliSession.clampReasoningEffort("none", "gpt-unknown"));
        assertEquals("low", CodexCliSession.clampReasoningEffort("none", null));
    }

    @Test
    public void returnsNullForNullBlankAndUnknown() {
        assertNull(CodexCliSession.clampReasoningEffort(null, "gpt-5.2"));
        assertNull(CodexCliSession.clampReasoningEffort("  ", "gpt-5.2"));
        assertNull(CodexCliSession.clampReasoningEffort("turbo", "gpt-5.2"));
    }

    @Test
    public void normalizesCaseAndWhitespace() {
        assertEquals("high", CodexCliSession.clampReasoningEffort(" HIGH ", "gpt-5.2"));
    }

    @Test
    public void longestModelPrefixWins() {
        // gpt-5.2 前缀先于 gpt-5 命中:xhigh 在 5.2 集内直发
        assertEquals("xhigh", CodexCliSession.clampReasoningEffort("xhigh", "gpt-5.2-codex"));
        // gpt-5.1 前缀先于 gpt-5 命中
        assertEquals("high", CodexCliSession.clampReasoningEffort("xhigh", "gpt-5.1-codex"));
    }
}
