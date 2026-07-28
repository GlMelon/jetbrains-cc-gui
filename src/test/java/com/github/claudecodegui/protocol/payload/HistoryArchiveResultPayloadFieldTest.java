package com.github.claudecodegui.protocol.payload;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class HistoryArchiveResultPayloadFieldTest {

    @Test
    public void archiveResultPayloadMatchesDeclaredWireFields() throws Exception {
        Class<?> resultType = Class.forName("com.github.claudecodegui.handler.history.HistoryBatchArchiveResult");
        var constructor = resultType.getDeclaredConstructor(List.class, List.class);
        constructor.setAccessible(true);
        Object result = constructor.newInstance(List.of("s1", "s2"), List.of("s1"));
        Method toPayload = resultType.getDeclaredMethod("toPayload");
        toPayload.setAccessible(true);
        JsonObject json = (JsonObject) toPayload.invoke(result);

        assertEquals(HistoryArchiveResultPayloadField.wireKeys(), json.keySet());
        assertEquals(List.of("s2"),
                json.getAsJsonArray(HistoryArchiveResultPayloadField.FAILED_SESSION_IDS.wireKey())
                        .asList().stream().map(element -> element.getAsString()).toList());
    }
}
