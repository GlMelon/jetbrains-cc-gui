package com.github.claudecodegui.handler.history;

record HistoryExportPayload(
        String json,
        boolean truncated,
        int exportedMessageCount,
        int omittedMessageCount,
        int utf8Bytes
) {
}
