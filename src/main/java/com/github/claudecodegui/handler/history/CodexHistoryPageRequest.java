package com.github.claudecodegui.handler.history;

/** Typed payload for persisted Codex history pagination. */
public record CodexHistoryPageRequest(String sessionId, Integer beforeTurn) {
}
