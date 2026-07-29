package com.github.claudecodegui.protocol.payload;

import com.github.claudecodegui.ui.bootstrap.WebviewBootstrapPayload;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class WebviewBootstrapPayloadFieldTest {

    @Test
    public void recordAndSerializedPayloadMatchDeclaredWireFields() {
        Set<String> recordComponents = new LinkedHashSet<>();
        for (RecordComponent component : WebviewBootstrapPayload.class.getRecordComponents()) {
            recordComponents.add(component.getName());
        }

        WebviewBootstrapPayload payload = new WebviewBootstrapPayload(
                new JsonObject(),
                new JsonObject(),
                new JsonObject(),
                new JsonObject(),
                new JsonObject(),
                new JsonObject()
        );
        JsonObject serialized = JsonParser.parseString(new Gson().toJson(payload)).getAsJsonObject();

        assertEquals(WebviewBootstrapPayloadField.wireKeys(), recordComponents);
        assertEquals(WebviewBootstrapPayloadField.wireKeys(), serialized.keySet());
        assertEquals(6, WebviewBootstrapPayloadField.values().length);
    }
}
