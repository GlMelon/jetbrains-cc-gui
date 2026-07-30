package com.github.claudecodegui.provider;

import com.google.gson.JsonObject;

import java.util.List;

/** Provider-neutral initial history load result. */
public record SessionHistoryLoadResult(List<JsonObject> messages, JsonObject pageInfo) {
    public SessionHistoryLoadResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static SessionHistoryLoadResult fromMessages(List<JsonObject> messages) {
        return new SessionHistoryLoadResult(messages, null);
    }
}
