package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.payload.HistoryArchiveResultPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Result of a backend-authoritative batch archive operation.
 */
record HistoryBatchArchiveResult(List<String> requestedSessionIds, List<String> archivedSessionIds) {
    HistoryBatchArchiveResult {
        requestedSessionIds = List.copyOf(requestedSessionIds);
        archivedSessionIds = List.copyOf(archivedSessionIds);
    }

    static HistoryBatchArchiveResult none(List<String> requestedSessionIds) {
        return new HistoryBatchArchiveResult(
                requestedSessionIds == null ? List.of() : requestedSessionIds,
                List.of()
        );
    }

    boolean success() {
        return !requestedSessionIds.isEmpty() && requestedSessionIds.size() == archivedSessionIds.size();
    }

    List<String> failedSessionIds() {
        Set<String> archived = new LinkedHashSet<>(archivedSessionIds);
        return requestedSessionIds.stream().filter(sessionId -> !archived.contains(sessionId)).toList();
    }

    JsonObject toPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty(HistoryArchiveResultPayloadField.SUCCESS.wireKey(), success());
        payload.add(HistoryArchiveResultPayloadField.REQUESTED_SESSION_IDS.wireKey(), toJsonArray(requestedSessionIds));
        payload.add(HistoryArchiveResultPayloadField.ARCHIVED_SESSION_IDS.wireKey(), toJsonArray(archivedSessionIds));
        payload.add(HistoryArchiveResultPayloadField.FAILED_SESSION_IDS.wireKey(), toJsonArray(failedSessionIds()));
        return payload;
    }

    private static JsonArray toJsonArray(List<String> values) {
        return GsonHolder.GSON.toJsonTree(values).getAsJsonArray();
    }
}
