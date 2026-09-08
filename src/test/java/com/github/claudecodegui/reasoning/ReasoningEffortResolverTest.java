package com.github.claudecodegui.reasoning;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link ReasoningEffortResolver#clamp} 分支覆盖。
 *
 * <p>强度序依赖 {@code ReasoningEffort} 声明顺序(ordinal):none/minimal/low/medium/high/xhigh/max。
 */
public class ReasoningEffortResolverTest {

    private static final List<String> CODEX_DEFAULT = List.of("low", "medium", "high", "xhigh");

    @Test
    public void nullOrEmptySupportedReturnsNull() {
        assertNull(ReasoningEffortResolver.clamp("high", null));
        assertNull(ReasoningEffortResolver.clamp("high", List.of()));
    }

    @Test
    public void nullOrBlankRequestedReturnsNull() {
        assertNull(ReasoningEffortResolver.clamp(null, CODEX_DEFAULT));
        assertNull(ReasoningEffortResolver.clamp("", CODEX_DEFAULT));
        assertNull(ReasoningEffortResolver.clamp("   ", CODEX_DEFAULT));
    }

    @Test
    public void exactMatchReturnsRequested() {
        assertEquals("high", ReasoningEffortResolver.clamp("high", CODEX_DEFAULT));
        assertEquals("xhigh", ReasoningEffortResolver.clamp("xhigh", CODEX_DEFAULT));
    }

    @Test
    public void unsupportedRequestedClampsDownToHighestSupportedBelow() {
        // max(6) 不在 codex 默认集 → 取严格小于 max 的最高支持档 xhigh(5)
        assertEquals("xhigh", ReasoningEffortResolver.clamp("max", CODEX_DEFAULT));
        // minimal(1) 不在 [low, medium, high] → 下方无档,回退最低档 low
        assertEquals("low", ReasoningEffortResolver.clamp("minimal", List.of("low", "medium", "high")));
    }

    @Test
    public void noLowerLevelFallsBackToLowestSupported() {
        // none(0) 不可能有更低档 → 列表最低档
        assertEquals("low", ReasoningEffortResolver.clamp("none", CODEX_DEFAULT));
        // grok-3-mini [low, high]:请求 medium(3) → 严格低于 medium 的最高档 low
        assertEquals("low", ReasoningEffortResolver.clamp("medium", List.of("low", "high")));
        // grok-3-mini [low, high]:请求 none(0) → 无更低档 → 最低档 low
        assertEquals("low", ReasoningEffortResolver.clamp("none", List.of("low", "high")));
    }

    @Test
    public void illegalRequestedValueReturnsNull() {
        assertNull(ReasoningEffortResolver.clamp("ultra", CODEX_DEFAULT));
        assertNull(ReasoningEffortResolver.clamp("off", CODEX_DEFAULT));
    }

    @Test
    public void caseAndWhitespaceNormalized() {
        assertEquals("high", ReasoningEffortResolver.clamp("  High  ", CODEX_DEFAULT));
        assertEquals("high", ReasoningEffortResolver.clamp("HIGH", List.of(" LOW ", "High")));
    }

    @Test
    public void illegalEntriesInSupportedListAreIgnored() {
        // 非法支持项被过滤,不参与 clamp
        assertEquals("medium", ReasoningEffortResolver.clamp("high", List.of("medium", "bogus")));
        // 全部非法 → 视同空列表
        assertNull(ReasoningEffortResolver.clamp("high", List.of("bogus", "ultra")));
    }
}
