package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.CliSendRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * kimi ACP 模型选择协商单测(纯静态函数直打)。
 *
 * <p>协议事实:模型合法值由 session/new / load 响应 configOptions 里 id="model" 的
 * select 项动态下发(实测 k3 / K2.7 / K2.7 Highspeed / K3-256k)。协商规则(与 thinking
 * 同范式,不硬编码词表):在目录(忽略大小写)→ 直发;不在目录 / 目录未知 / 哨兵值 → 不发。
 */
public class KimiAcpModelNegotiationTest {

    // ── parseModelOptions ────────────────────────────────────────────────────

    @Test
    public void parseModelOptionsReadsModelEntry() {
        String json = """
                {"sessionId":"s1","configOptions":[
                  {"type":"select","id":"model","category":"model","currentValue":"k3",
                   "options":[{"value":"k3"},{"value":"K2.7"},{"value":"K2.7 Highspeed"},{"value":"K3-256k"}]},
                  {"type":"select","id":"thinking","category":"thought_level",
                   "options":[{"value":"off"},{"value":"high"}]}
                ]}
                """;
        KimiAcpCliSession.ModelOptions options =
                KimiAcpCliSession.parseModelOptions(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(List.of("k3", "K2.7", "K2.7 Highspeed", "K3-256k"), options.supportedValues());
        assertEquals("k3", options.currentValue());
    }

    @Test
    public void parseModelOptionsReturnsNullWhenFieldAbsent() {
        assertNull(KimiAcpCliSession.parseModelOptions(new JsonObject()));
        assertNull(KimiAcpCliSession.parseModelOptions(null));
    }

    @Test
    public void parseModelOptionsReturnsNullWhenNoModelEntry() {
        String json = """
                {"sessionId":"s1","configOptions":[
                  {"type":"select","id":"thinking","category":"thought_level",
                   "options":[{"value":"off"},{"value":"high"}]}
                ]}
                """;
        assertNull(KimiAcpCliSession.parseModelOptions(JsonParser.parseString(json).getAsJsonObject()));
    }

    // ── resolveDesiredModel ──────────────────────────────────────────────────

    private static CliSendRequest requestWithModel(String model, String actualModel) {
        return new CliSendRequest("tab1", "kimi", "hi", null, null,
                List.of(), null, List.of(), null, null,
                model, actualModel, null, null, Map.of());
    }

    @Test
    public void resolvePrefersActualModel() {
        assertEquals("K2.7", KimiAcpCliSession.resolveDesiredModel(requestWithModel("role-x", "K2.7")));
    }

    @Test
    public void resolveFallsBackToModel() {
        assertEquals("k3", KimiAcpCliSession.resolveDesiredModel(requestWithModel("k3", null)));
        assertEquals("k3", KimiAcpCliSession.resolveDesiredModel(requestWithModel("k3", "  ")));
    }

    @Test
    public void resolveFiltersSentinels() {
        assertNull(KimiAcpCliSession.resolveDesiredModel(requestWithModel("default", null)));
        assertNull(KimiAcpCliSession.resolveDesiredModel(requestWithModel("AUTO", null)));
        assertNull(KimiAcpCliSession.resolveDesiredModel(requestWithModel("__config_default__", null)));
        assertNull(KimiAcpCliSession.resolveDesiredModel(requestWithModel(null, null)));
        assertNull(KimiAcpCliSession.resolveDesiredModel(null));
    }

    // ── negotiateModelValue ──────────────────────────────────────────────────

    private static KimiAcpCliSession.ModelOptions catalog() {
        return new KimiAcpCliSession.ModelOptions(
                List.of("k3", "K2.7", "K2.7 Highspeed", "K3-256k"), "k3");
    }

    @Test
    public void negotiateKeepsCatalogEntry() {
        assertEquals("K2.7", KimiAcpCliSession.negotiateModelValue("K2.7", catalog()));
    }

    @Test
    public void negotiateMatchesCaseInsensitiveAndReturnsCatalogSpelling() {
        assertEquals("K3-256k", KimiAcpCliSession.negotiateModelValue("k3-256K", catalog()));
    }

    @Test
    public void negotiateReturnsNullWhenNotInCatalog() {
        assertNull(KimiAcpCliSession.negotiateModelValue("some-other-model", catalog()));
    }

    @Test
    public void negotiateReturnsNullWhenCatalogUnknown() {
        assertNull(KimiAcpCliSession.negotiateModelValue("k3", null));
        assertNull(KimiAcpCliSession.negotiateModelValue("k3",
                new KimiAcpCliSession.ModelOptions(List.of(), null)));
    }

    @Test
    public void negotiateReturnsNullWhenDesiredNull() {
        assertNull(KimiAcpCliSession.negotiateModelValue(null, catalog()));
    }
}
