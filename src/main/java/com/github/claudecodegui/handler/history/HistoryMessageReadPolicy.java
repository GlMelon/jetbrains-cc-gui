package com.github.claudecodegui.handler.history;

/** Limits applied while reading provider-native history into export memory. */
record HistoryMessageReadPolicy(int maxMessageCount, int maxUtf8Bytes) {
    HistoryMessageReadPolicy {
        if (maxMessageCount < 0) {
            throw new IllegalArgumentException("maxMessageCount must be non-negative");
        }
        if (maxUtf8Bytes < 0) {
            throw new IllegalArgumentException("maxUtf8Bytes must be non-negative");
        }
    }
}
