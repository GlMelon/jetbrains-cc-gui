package com.github.claudecodegui.ui.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class WebviewBootstrapPayloadFactoryTest {

    @Test
    public void payloadSerializesNestedJsonAsObjects() {
        WebviewBootstrapPayload payload = new WebviewBootstrapPayload(
                WebviewBootstrapPayloadFactory.parseObject("{\"fontFamily\":\"JetBrains Mono\"}"),
                WebviewBootstrapPayloadFactory.parseObject("{\"mode\":\"followEditor\"}"),
                WebviewBootstrapPayloadFactory.parseObject("{\"mode\":\"customFile\"}"),
                WebviewBootstrapPayloadFactory.parseObject("{\"language\":\"zh-CN\"}"),
                WebviewBootstrapPayloadFactory.parseObject("{\"themePreference\":\"system\"}"),
                WebviewBootstrapPayloadFactory.parseObject("{\"mode\":\"builtIn\"}")
        );

        JsonObject json = JsonParser.parseString(new Gson().toJson(payload)).getAsJsonObject();

        Assert.assertTrue(json.get("editorFontConfig").isJsonObject());
        Assert.assertTrue(json.get("uiFontConfig").isJsonObject());
        Assert.assertTrue(json.get("codeFontConfig").isJsonObject());
        Assert.assertTrue(json.get("languageConfig").isJsonObject());
        Assert.assertTrue(json.get("appearanceConfig").isJsonObject());
        Assert.assertTrue(json.get("avatarConfig").isJsonObject());
    }
}
