package com.github.claudecodegui.protocol.payload;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class HistoryExportPayloadFieldTest {

    @Test
    public void builderSuccessPayloadMatchesDeclaredWireFields() throws Exception {
        Class<?> policyType = Class.forName("com.github.claudecodegui.handler.history.HistoryExportPolicy");
        var policyConstructor = policyType.getDeclaredConstructor(int.class, int.class);
        policyConstructor.setAccessible(true);
        Object policy = policyConstructor.newInstance(1, 1024);

        Class<?> builderType = Class.forName("com.github.claudecodegui.handler.history.HistoryExportPayloadBuilder");
        var builderConstructor = builderType.getDeclaredConstructor(policyType);
        builderConstructor.setAccessible(true);
        Object builder = builderConstructor.newInstance(policy);
        Class<?> batchType = Class.forName("com.github.claudecodegui.handler.history.HistoryMessageBatch");
        var batchConstructor = batchType.getDeclaredConstructor(java.util.List.class, int.class);
        batchConstructor.setAccessible(true);
        Object batch = batchConstructor.newInstance(java.util.List.of(), 0);

        Method build = builderType.getDeclaredMethod("build", String.class, String.class, batchType);
        build.setAccessible(true);
        Object payload = build.invoke(builder, "session-1", "Demo", batch);
        Method jsonMethod = payload.getClass().getDeclaredMethod("json");
        jsonMethod.setAccessible(true);
        JsonObject json = JsonParser.parseString((String) jsonMethod.invoke(payload)).getAsJsonObject();

        Set<String> expected = new java.util.LinkedHashSet<>(HistoryExportPayloadField.wireKeys());
        expected.remove(HistoryExportPayloadField.ERROR.wireKey());
        assertEquals(expected, json.keySet());
    }
}
