package com.github.claudecodegui.handler.history;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable renderer input for one bounded export candidate. */
record HistoryExportDocument(
        String sessionId,
        String title,
        List<JsonObject> messages,
        int exportedMessageCount,
        int totalMessageCount,
        HistoryExportPolicy policy
) {
    HistoryExportDocument {
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        if (exportedMessageCount < 0 || totalMessageCount < exportedMessageCount) {
            throw new IllegalArgumentException("Invalid history export message counts");
        }
    }

    int omittedMessageCount() {
        return totalMessageCount - exportedMessageCount;
    }

    boolean truncated() {
        return omittedMessageCount() > 0;
    }
}
