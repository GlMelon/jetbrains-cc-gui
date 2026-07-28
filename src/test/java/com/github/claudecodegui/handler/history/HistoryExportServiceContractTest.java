package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryExportServiceContractTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/github/claudecodegui/handler/history/HistoryExportService.java"
    );
    private static final Path BUILDER = Path.of(
            "src/main/java/com/github/claudecodegui/handler/history/HistoryExportPayloadBuilder.java"
    );

    @Test
    public void dispatchesThroughTypedHistoryEventWithoutLegacyJavascriptInjection() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("context.dispatchEvent("));
        assertTrue(source.contains("DownstreamEvent.HISTORY_EXPORT_DATA.value()"));
        assertFalse(source.contains("onExportSessionData"));
        assertFalse(source.contains("executeJavaScriptOnEDT"));
        assertFalse(source.contains("Base64"));
        assertFalse(source.contains("ApplicationManager"));
        assertTrue(source.contains("payloadBuilder.messageReadPolicy()"));
    }

    @Test
    public void boundedBuilderAvoidsSecondJsonTreeAndBase64Copy() throws Exception {
        String source = Files.readString(BUILDER, StandardCharsets.UTF_8);

        assertTrue(source.contains("policy.maxMessageCount()"));
        assertTrue(source.contains("policy.maxUtf8Bytes()"));
        assertFalse(source.contains("import com.google.gson.JsonArray;"));
        assertFalse(source.contains("Base64.getEncoder()"));
    }

    @Test
    public void printPdfReusesBoundedHtmlRendererAndOpensBrowserWithoutBinaryTransport() throws Exception {
        // Print-to-PDF must reuse the sanitized, budget-bounded HTML renderer and hand off to the
        // system browser — never round-trip binary PDF content or reintroduce Base64 transport.
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue("must reuse the bounded HTML renderer", source.contains("HistoryExportFormat.HTML"));
        assertTrue("must build via the shared payload builder", source.contains("payloadBuilder.build("));
        assertTrue("must open the transcript in the system browser", source.contains("BrowserUtil.browse"));
        assertTrue("must report success via the typed toast event",
                source.contains("DownstreamEvent.TOAST_SUCCESS"));
        assertTrue("must report failure via the typed toast event",
                source.contains("DownstreamEvent.TOAST_ERROR"));
        assertFalse("must not encode binary content as Base64", source.contains("Base64"));
        assertFalse("must not require the EDT explicitly (dispatchEvent is thread-safe)",
                source.contains("ApplicationManager"));
        assertFalse("print must not round-trip via the download save action",
                source.contains("save_exported_file"));
    }
}
