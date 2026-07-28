package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for the print-to-PDF helpers in {@link HistoryExportService}.
 *
 * <p>The helpers are pure (no HandlerContext, no platform browser API) so they can be unit-tested
 * without an Application/keychain. The actual {@code BrowserUtil.browse} call is the only untested
 * platform boundary, consistent with how the rest of the export layer treats platform calls.</p>
 */
public class HistoryPrintPdfTest {

    @Test
    public void extractHtmlContentReadsContentFieldFromEnvelope() {
        HistoryExportPayload payload = new HistoryExportPayload(
                "{\"content\":\"<html>hi</html>\"}", false, 1, 0, 10);
        assertEquals("<html>hi</html>", HistoryExportService.extractHtmlContent(payload));
    }

    @Test
    public void extractHtmlContentReturnsEmptyWhenContentMissing() {
        HistoryExportPayload payload = new HistoryExportPayload("{\"sessionId\":\"s\"}", false, 0, 0, 0);
        assertEquals("", HistoryExportService.extractHtmlContent(payload));
    }

    @Test
    public void extractHtmlContentReturnsEmptyForNullContent() {
        HistoryExportPayload payload = new HistoryExportPayload("{\"content\":null}", false, 0, 0, 0);
        assertEquals("", HistoryExportService.extractHtmlContent(payload));
    }

    @Test
    public void writePrintHtmlFileWritesExactUtf8Content() throws Exception {
        // Mix ASCII + multibyte UTF-8 to confirm byte-accurate write (no platform encoding leak).
        String html = "<!doctype html><p>éü中ñСт</p>";
        Path file = HistoryExportService.writePrintHtmlFile(html, "abc-123");
        try {
            assertTrue(Files.exists(file));
            assertEquals(html, Files.readString(file, StandardCharsets.UTF_8));
            String name = file.getFileName().toString();
            assertTrue(name, name.startsWith("codemoss-history-"));
            assertTrue(name, name.endsWith(".html"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void writePrintHtmlFileRejectsPathSeparatorsFromSessionId() throws Exception {
        // The sessionId must not let path separators / traversal escape the temp directory.
        Path file = HistoryExportService.writePrintHtmlFile("x", "..\\..\\evil/path?:*");
        try {
            String name = file.getFileName().toString();
            assertTrue("filename must not contain path separators: " + name,
                    !name.contains("/") && !name.contains("\\") && !name.contains(":"));
            assertTrue("filename must not allow traversal: " + name, !name.contains(".."));
            assertTrue(name, name.startsWith("codemoss-history-"));
            assertTrue(name, name.endsWith(".html"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void writePrintHtmlFileHandlesBlankAndNullSessionId() throws Exception {
        Path blank = HistoryExportService.writePrintHtmlFile("html", "   ");
        Path nullId = HistoryExportService.writePrintHtmlFile("html", null);
        try {
            assertTrue(blank.getFileName().toString().startsWith("codemoss-history-session-"));
            assertTrue(nullId.getFileName().toString().startsWith("codemoss-history-session-"));
            assertEquals("html", Files.readString(blank, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(blank);
            Files.deleteIfExists(nullId);
        }
    }
}
