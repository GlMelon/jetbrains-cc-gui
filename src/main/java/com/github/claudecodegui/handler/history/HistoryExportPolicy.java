package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.payload.HistoryExportLimits;

/** Backend-owned limits for a single history export payload. */
final class HistoryExportPolicy {
    static final int DEFAULT_MAX_MESSAGE_COUNT = HistoryExportLimits.DEFAULT_MAX_MESSAGE_COUNT;
    static final int DEFAULT_MAX_UTF8_BYTES = HistoryExportLimits.DEFAULT_MAX_UTF8_BYTES;
    static final int MIN_MAX_UTF8_BYTES = HistoryExportLimits.MIN_MAX_UTF8_BYTES;

    private final int maxMessageCount;
    private final int maxUtf8Bytes;

    HistoryExportPolicy() {
        this(DEFAULT_MAX_MESSAGE_COUNT, DEFAULT_MAX_UTF8_BYTES);
    }

    HistoryExportPolicy(int maxMessageCount, int maxUtf8Bytes) {
        if (maxMessageCount < 0) {
            throw new IllegalArgumentException("maxMessageCount must be non-negative");
        }
        if (maxUtf8Bytes < MIN_MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("maxUtf8Bytes must be at least " + MIN_MAX_UTF8_BYTES);
        }
        this.maxMessageCount = maxMessageCount;
        this.maxUtf8Bytes = maxUtf8Bytes;
    }

    int maxMessageCount() {
        return maxMessageCount;
    }

    int maxUtf8Bytes() {
        return maxUtf8Bytes;
    }

    HistoryMessageReadPolicy messageReadPolicy() {
        return new HistoryMessageReadPolicy(maxMessageCount, maxUtf8Bytes);
    }
}
