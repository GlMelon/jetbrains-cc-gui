package com.github.claudecodegui.handler.history;

record HistoryArchiveResult(boolean archived) {
    static HistoryArchiveResult none() {
        return new HistoryArchiveResult(false);
    }
}
