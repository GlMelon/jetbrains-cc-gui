package com.github.claudecodegui.handler.history;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BoundedHistoryMessageCollectorTest {

    @Test
    public void retainsOnlyLeadingCountBudgetWhileCountingAllMessages() {
        BoundedHistoryMessageCollector collector = new BoundedHistoryMessageCollector(
                new HistoryMessageReadPolicy(2, 4096)
        );

        collector.append(message("one"));
        collector.append(message("two"));
        collector.append(message("three"));

        HistoryMessageBatch batch = collector.toBatch();
        assertEquals(2, batch.messages().size());
        assertEquals(3, batch.totalMessageCount());
    }

    @Test
    public void rejectsOversizedLeadingMessageAndDoesNotRetainLaterMessages() {
        BoundedHistoryMessageCollector collector = new BoundedHistoryMessageCollector(
                new HistoryMessageReadPolicy(10, 32)
        );

        collector.append(message("x".repeat(100)));
        collector.append(message("small"));

        HistoryMessageBatch batch = collector.toBatch();
        assertTrue(batch.messages().isEmpty());
        assertEquals(2, batch.totalMessageCount());
    }

    private static JsonObject message(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        return message;
    }
}
