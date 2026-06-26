package com.github.claudecodegui.common;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link ClaudeRole#reasoningLevels()} 行为锁定。
 *
 * <p>低危清理③:reasoningLevels() 字面量 → {@link com.github.claudecodegui.protocol.ReasoningEffort}
 * 枚举 SSOT 引用的等价重构保护。reasoningLevels() 是展示层子集过滤(全集 5 档由 ReasoningEffort 承载),
 * 各 role 返回的字符串列表必须与枚举 {@code value()} 完全一致(值与顺序)。本测试在重构前后都应绿。
 */
public class ClaudeRoleReasoningLevelsTest {

    @Test
    public void sonnetOpusFableExposeFullFiveLevels() {
        List<String> expected = List.of("low", "medium", "high", "xhigh", "max");
        assertEquals(expected, ClaudeRole.SONNET.reasoningLevels());
        assertEquals(expected, ClaudeRole.OPUS.reasoningLevels());
        assertEquals(expected, ClaudeRole.FABLE.reasoningLevels());
    }

    @Test
    public void haikuExposesOnlyFirstThreeLevels() {
        assertEquals(List.of("low", "medium", "high"), ClaudeRole.HAIKU.reasoningLevels());
    }
}
