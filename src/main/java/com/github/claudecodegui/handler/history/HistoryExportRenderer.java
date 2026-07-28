package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;

/** Renders one backend-owned history export file format. */
interface HistoryExportRenderer {
    HistoryExportFormat format();

    String render(HistoryExportDocument document);
}
