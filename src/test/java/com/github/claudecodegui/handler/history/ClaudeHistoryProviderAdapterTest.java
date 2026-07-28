package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class ClaudeHistoryProviderAdapterTest {

    @Test
    public void readsBoundedPrefixWhileCountingEveryValidJsonlMessage() throws Exception {
        Path sessionFile = Files.createTempFile("claude-history-bounded", ".jsonl");
        try {
            Files.writeString(
                    sessionFile,
                    line("one") + "\n" + line("two") + "\n" + line("three") + "\nnot-json\n",
                    StandardCharsets.UTF_8
            );

            HistoryMessageBatch batch = ClaudeHistoryProviderAdapter.loadMessagesFromFile(
                    sessionFile,
                    new HistoryMessageReadPolicy(2, 4096)
            );

            assertEquals(2, batch.messages().size());
            assertEquals(3, batch.totalMessageCount());
            assertEquals("one", batch.messages().get(0).getAsJsonObject("message")
                    .get("content").getAsString());
        } finally {
            Files.deleteIfExists(sessionFile);
        }
    }

    private static String line(String content) {
        return "{\"sessionId\":\"session-1\",\"type\":\"user\","
                + "\"message\":{\"role\":\"user\",\"content\":\"" + content + "\"}}";
    }
}
