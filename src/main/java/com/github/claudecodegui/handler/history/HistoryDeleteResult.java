package com.github.claudecodegui.handler.history;

record HistoryDeleteResult(boolean mainDeleted, int agentFilesDeleted) {
    static HistoryDeleteResult none() {
        return new HistoryDeleteResult(false, 0);
    }
}
