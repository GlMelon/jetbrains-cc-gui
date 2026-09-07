package com.github.claudecodegui.cli.opencode.serve;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * opencode serve model 拆分单测:"provider/model" → {providerID, modelID};
 * 无 '/' 或段为空返回 null(调用方告警并省略,用 serve 会话默认模型)。
 */
public class OpenCodeServeModelRefTest {

    @Test
    public void splitsProviderAndModel() {
        JsonObject ref = OpenCodeServeSession.splitModelRef("openglm/glm-5.2");
        assertEquals("openglm", ref.get("providerID").getAsString());
        assertEquals("glm-5.2", ref.get("modelID").getAsString());
    }

    @Test
    public void modelIdMayContainFurtherSlashes() {
        JsonObject ref = OpenCodeServeSession.splitModelRef("openai/gpt/6");
        assertEquals("openai", ref.get("providerID").getAsString());
        assertEquals("gpt/6", ref.get("modelID").getAsString());
    }

    @Test
    public void returnsNullWithoutSeparator() {
        assertNull(OpenCodeServeSession.splitModelRef("glm-5.2"));
        assertNull(OpenCodeServeSession.splitModelRef(null));
    }

    @Test
    public void returnsNullWhenEitherSegmentEmpty() {
        assertNull(OpenCodeServeSession.splitModelRef("/glm-5.2"));
        assertNull(OpenCodeServeSession.splitModelRef("openglm/"));
    }
}
