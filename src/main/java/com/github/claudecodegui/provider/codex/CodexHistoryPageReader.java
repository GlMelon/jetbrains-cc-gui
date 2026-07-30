package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.handler.history.HistoryMessageInjector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/** Reads persisted Codex history and converts it to frontend message objects. */
public class CodexHistoryPageReader {
    private final CodexHistoryReader historyReader;

    public CodexHistoryPageReader(CodexHistoryReader historyReader) {
        if (historyReader == null) {
            throw new IllegalArgumentException("historyReader is required");
        }
        this.historyReader = historyReader;
    }

    public List<JsonObject> readFrontendMessages(String sessionId) {
        JsonArray historyItems = new JsonArray();
        try {
            historyReader.forEachSessionMessage(sessionId, historyItems::add);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream Codex history for session: " + sessionId, e);
        }
        return convertHistoryItems(historyItems);
    }

    static List<JsonObject> convertHistoryItems(JsonArray historyItems) {
        if (historyItems == null) {
            return List.of();
        }
        return HistoryMessageInjector.convertCodexMessagesToFrontendBatch(historyItems);
    }
}
