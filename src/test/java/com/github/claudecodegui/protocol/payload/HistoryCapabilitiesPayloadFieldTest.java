package com.github.claudecodegui.protocol.payload;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class HistoryCapabilitiesPayloadFieldTest {

    @Test
    public void capabilitiesPayloadMatchesDeclaredWireFields() throws Exception {
        Class<?> capabilitiesType = Class.forName("com.github.claudecodegui.handler.history.HistoryCapabilities");
        var constructor = capabilitiesType.getDeclaredConstructor(boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object capabilities = constructor.newInstance(true, false);
        Method toJson = capabilitiesType.getDeclaredMethod("toJson");
        toJson.setAccessible(true);
        JsonObject json = (JsonObject) toJson.invoke(capabilities);

        assertEquals(HistoryCapabilitiesPayloadField.wireKeys(), json.keySet());
        assertEquals("capabilities", HistoryCapabilitiesPayloadField.ROOT_WIRE_KEY);
    }
}
