package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Counts every normalized source message while retaining only the leading budgeted prefix. */
final class BoundedHistoryMessageCollector implements HistoryMessageInjector.CodexMessageSink {
    private final HistoryMessageReadPolicy policy;
    private final List<JsonObject> messages = new ArrayList<>();
    private final List<Integer> serializedBytes = new ArrayList<>();
    private int retainedUtf8Bytes;
    private int totalMessageCount;
    private boolean retentionClosed;

    BoundedHistoryMessageCollector(HistoryMessageReadPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void append(JsonObject message) {
        totalMessageCount++;
        if (retentionClosed || messages.size() >= policy.maxMessageCount()) {
            retentionClosed = true;
            return;
        }

        JsonObject normalized = message == null ? new JsonObject() : message;
        int messageBytes = utf8Length(GsonHolder.GSON.toJson(normalized));
        int separatorBytes = messages.isEmpty() ? 0 : 1;
        if (retainedUtf8Bytes + separatorBytes + messageBytes > policy.maxUtf8Bytes()) {
            retentionClosed = true;
            return;
        }

        messages.add(normalized);
        serializedBytes.add(messageBytes);
        retainedUtf8Bytes += separatorBytes + messageBytes;
    }

    @Override
    public void replaceLast(JsonObject message) {
        if (totalMessageCount == 0 || totalMessageCount > messages.size()) {
            return;
        }

        int lastIndex = messages.size() - 1;
        JsonObject normalized = message == null ? new JsonObject() : message;
        int replacementBytes = utf8Length(GsonHolder.GSON.toJson(normalized));
        int candidateBytes = retainedUtf8Bytes - serializedBytes.get(lastIndex) + replacementBytes;
        if (candidateBytes > policy.maxUtf8Bytes()) {
            int separatorBytes = lastIndex == 0 ? 0 : 1;
            retainedUtf8Bytes -= serializedBytes.remove(lastIndex) + separatorBytes;
            messages.remove(lastIndex);
            retentionClosed = true;
            return;
        }

        messages.set(lastIndex, normalized);
        serializedBytes.set(lastIndex, replacementBytes);
        retainedUtf8Bytes = candidateBytes;
    }

    HistoryMessageBatch toBatch() {
        return new HistoryMessageBatch(messages, totalMessageCount);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
