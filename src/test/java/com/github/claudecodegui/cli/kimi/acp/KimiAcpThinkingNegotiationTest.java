package com.github.claudecodegui.cli.kimi.acp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * kimi thinking 档位协商单测(纯静态函数直打)。
 *
 * <p>协议事实:thinking 合法值由当前模型目录动态下发(session/new / load 响应
 * configOptions 里 category=thought_level 的 select 项)。UI 的 low/medium/high 是
 * 通用档位,不一定在词表里——协商规则:支持就直发 → 就近 effort 档 → "on" 万能别名
 * → 省略不发(对齐 opencode mapReasoningVariant 的「不支持就省略」范式)。
 */
public class KimiAcpThinkingNegotiationTest {

    // ── parseThinkingOptions ─────────────────────────────────────────────────

    @Test
    public void parseThinkingOptionsReadsSelectEntry() {
        String json = """
                {"sessionId":"s1","configOptions":[
                  {"type":"select","id":"model","category":"model","options":[{"value":"k3"}]},
                  {"type":"select","id":"thinking","name":"Thinking","category":"thought_level",
                   "currentValue":"off",
                   "options":[{"value":"off","name":"Off"},{"value":"low","name":"Low"},{"value":"high","name":"High"}]}
                ]}
                """;
        KimiAcpCliSession.ThinkingOptions options =
                KimiAcpCliSession.parseThinkingOptions(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        assertEquals(List.of("off", "low", "high"), options.supportedValues());
        assertEquals("off", options.currentValue());
    }

    @Test
    public void parseThinkingOptionsReturnsNullWhenFieldAbsent() {
        // 旧版 kimi / 字段可省(ACP LoadSessionResponse 所有字段 optional)
        assertNull(KimiAcpCliSession.parseThinkingOptions(new JsonObject()));
        assertNull(KimiAcpCliSession.parseThinkingOptions(null));
    }

    @Test
    public void parseThinkingOptionsReturnsNullWhenNoThinkingEntry() {
        String json = """
                {"sessionId":"s1","configOptions":[
                  {"type":"select","id":"model","category":"model","options":[{"value":"k3"}]}
                ]}
                """;
        assertNull(KimiAcpCliSession.parseThinkingOptions(com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
    }

    // ── negotiateThinkingValue ───────────────────────────────────────────────

    @Test
    public void negotiatePassesThroughWhenCatalogUnknown() {
        // 旧版 kimi 无 configOptions:保持字面量直发(legacy 行为,失败非致命)
        assertEquals("medium", KimiAcpCliSession.negotiateThinkingValue("medium", null));
    }

    @Test
    public void negotiateReturnsNullWhenDesiredNull() {
        assertNull(KimiAcpCliSession.negotiateThinkingValue(null,
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "low"), "off")));
    }

    @Test
    public void negotiateKeepsExactMatch() {
        KimiAcpCliSession.ThinkingOptions options =
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "low", "medium", "high"), "off");
        assertEquals("high", KimiAcpCliSession.negotiateThinkingValue("high", options));
        assertEquals("medium", KimiAcpCliSession.negotiateThinkingValue("medium", options));
    }

    @Test
    public void negotiateMapsToNearestEffort() {
        // 词表无 medium:medium(1) → minimal(1) 同 rank 最近
        KimiAcpCliSession.ThinkingOptions options =
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "minimal", "low", "high", "max"), "off");
        assertEquals("minimal", KimiAcpCliSession.negotiateThinkingValue("medium", options));
        assertEquals("low", KimiAcpCliSession.negotiateThinkingValue("low", options));
        // xhigh/max 同 rank=3,max 存在则直发
        assertEquals("max", KimiAcpCliSession.negotiateThinkingValue("xhigh", options));
    }

    @Test
    public void negotiateTieBreaksToLowerEffort() {
        // low(0) 与 high(2) 距 medium(1) 等距 → 取低档(烧 token 保守)
        KimiAcpCliSession.ThinkingOptions options =
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "low", "high"), "off");
        assertEquals("low", KimiAcpCliSession.negotiateThinkingValue("medium", options));
    }

    @Test
    public void negotiateFallsBackToOnWhenNoEffortWords() {
        // 模型无 effort 目录:词表仅 off/on → "on"(kimi 侧解析为 defaultThinkingEffort)
        KimiAcpCliSession.ThinkingOptions onOff =
                new KimiAcpCliSession.ThinkingOptions(List.of("off", "on"), "off");
        assertEquals("on", KimiAcpCliSession.negotiateThinkingValue("medium", onOff));

        // alwaysThinking 模型:词表仅 on
        KimiAcpCliSession.ThinkingOptions onOnly =
                new KimiAcpCliSession.ThinkingOptions(List.of("on"), "on");
        assertEquals("on", KimiAcpCliSession.negotiateThinkingValue("low", onOnly));
    }

    @Test
    public void negotiateReturnsNullWhenOnlyOffAvailable() {
        // 词表只有 off(受限配置):无可协商目标 → 不发
        KimiAcpCliSession.ThinkingOptions options =
                new KimiAcpCliSession.ThinkingOptions(List.of("off"), "off");
        assertNull(KimiAcpCliSession.negotiateThinkingValue("medium", options));
    }

    @Test
    public void negotiatePassesThroughOnEmptyCatalog() {
        // 空词表 = 目录未知(parseThinkingOptions 不会产出空表,此为防御语义)→ 字面量透传
        assertEquals("medium", KimiAcpCliSession.negotiateThinkingValue("medium",
                new KimiAcpCliSession.ThinkingOptions(List.of(), "off")));
    }
}
