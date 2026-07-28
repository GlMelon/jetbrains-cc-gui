package com.github.claudecodegui.handler.history;

import com.google.gson.JsonObject;

import java.util.List;

/** Provider history messages materialized within a read budget plus the exact source count. */
record HistoryMessageBatch(List<JsonObject> messages, int totalMessageCount) {
    HistoryMessageBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (totalMessageCount < messages.size()) {
            throw new IllegalArgumentException("totalMessageCount cannot be smaller than loaded messages");
        }
    }

    static HistoryMessageBatch empty() {
        return new HistoryMessageBatch(List.of(), 0);
    }
}
