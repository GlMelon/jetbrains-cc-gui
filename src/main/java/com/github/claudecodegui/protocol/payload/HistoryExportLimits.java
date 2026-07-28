package com.github.claudecodegui.protocol.payload;

/** Shared size limits for history export generation, transport, and persistence. */
public final class HistoryExportLimits {
    public static final int DEFAULT_MAX_MESSAGE_COUNT = 10_000;
    public static final int DEFAULT_MAX_UTF8_BYTES = 8 * 1024 * 1024;
    public static final int MIN_MAX_UTF8_BYTES = 512;

    private HistoryExportLimits() {
    }
}
