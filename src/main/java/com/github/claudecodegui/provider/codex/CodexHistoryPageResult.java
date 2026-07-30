package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;

import java.util.List;

/** Codex history page messages plus protocol metadata. */
public record CodexHistoryPageResult(List<JsonObject> messages, JsonObject pageInfo) {
    public CodexHistoryPageResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (pageInfo == null) {
            throw new IllegalArgumentException("pageInfo is required");
        }
    }
}
