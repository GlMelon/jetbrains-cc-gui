package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * opencode reasoningEffort → variant 解析验证(unknown variant 模型解析 fail-fast,必须本地拦;
 * one-shot 与 serve prompt_async 的 variant 字段共用,SSOT):
 * <ul>
 *   <li>无动态目录(null)→ 内置安全子集 [low,medium,high] clamp 后走 mapReasoningVariant
 *       (xhigh/max 钳 high,不盲发);</li>
 *   <li>动态目录 → 档位集 = 目录 id 反查协议档位,clamp 后取目录内实际 id
 *       (LOW 优先 minimal;MEDIUM 省略 = 默认档;空/未知 id 目录 → null 不携带)。</li>
 * </ul>
 */
public class ReasoningVariantResolutionTest {

    // ── 内置回退(availableVariantIds == null,one-shot / serve 查询失败) ────────

    @Test
    public void builtinFallbackKeepsLegacyMappingForLowMediumHigh() {
        assertEquals("minimal", AbstractRunOnceCliSession.resolveReasoningVariant("low", null, null));
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant("medium", null, null));
        assertEquals("high", AbstractRunOnceCliSession.resolveReasoningVariant("high", null, null));
    }

    @Test
    public void builtinFallbackClampsXhighAndMaxToHigh() {
        assertEquals("high", AbstractRunOnceCliSession.resolveReasoningVariant("xhigh", "xai/grok-4", null));
        assertEquals("high", AbstractRunOnceCliSession.resolveReasoningVariant("max", null, null));
    }

    @Test
    public void builtinFallbackReturnsNullForNullBlankUnknownAndNone() {
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant(null, null, null));
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant(" ", null, null));
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant("turbo", null, null));
        // 内置安全子集无 none:none → 无更低支持档 → 最低 low → minimal
        assertEquals("minimal", AbstractRunOnceCliSession.resolveReasoningVariant("none", null, null));
    }

    // ── 动态目录(serve GET /provider) ────────────────────────────────────────

    @Test
    public void dynamicCatalogPassesThroughSupportedVariant() {
        assertEquals("high",
                AbstractRunOnceCliSession.resolveReasoningVariant("high", "openglm/glm-5.2", List.of("high", "max")));
        assertEquals("max",
                AbstractRunOnceCliSession.resolveReasoningVariant("max", "openglm/glm-5.2", List.of("high", "max")));
        // 通用 clamp = floor 语义(取不超过请求值的最高支持档):xhigh 在 [high,max] 上钳到 high
        // (向上映射成 max 是 kimi wire 表的特例,不进通用 clamp)
        assertEquals("high",
                AbstractRunOnceCliSession.resolveReasoningVariant("xhigh", "openglm/glm-5.2", List.of("high", "max")));
    }

    @Test
    public void dynamicCatalogPrefersMinimalForLow() {
        assertEquals("minimal",
                AbstractRunOnceCliSession.resolveReasoningVariant("low", null, List.of("minimal", "high")));
        assertEquals("low",
                AbstractRunOnceCliSession.resolveReasoningVariant("low", null, List.of("low", "high")));
    }

    @Test
    public void dynamicCatalogClampsToHighestSupportedBelowRequest() {
        // 目录仅 [high]:low 请求 → 无更低支持档 → 取最低(唯一)high
        assertEquals("high", AbstractRunOnceCliSession.resolveReasoningVariant("low", null, List.of("high")));
        // 目录 [minimal,high]:xhigh → 钳 high
        assertEquals("high",
                AbstractRunOnceCliSession.resolveReasoningVariant("xhigh", null, List.of("minimal", "high")));
    }

    @Test
    public void dynamicCatalogOmitsVariantForMediumAndEmptyAndUnknownIds() {
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant("medium", null, List.of("medium", "high")));
        // 空目录 = 模型明确无 variant:不携带(携带即 fail-fast)
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant("high", null, List.of()));
        // 未知 id 目录(非协议档位词):无已知档位 → 不携带
        assertNull(AbstractRunOnceCliSession.resolveReasoningVariant("high", null, List.of("beam")));
    }
}
