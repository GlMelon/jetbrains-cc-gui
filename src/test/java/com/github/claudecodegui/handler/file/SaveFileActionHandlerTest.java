package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SaveFileActionHandlerTest {
    private static final Path FILE_EXPORT_UTILS = Path.of(
            "src/main/java/com/github/claudecodegui/handler/file/FileExportUtils.java"
    );

    private final SaveMarkdownActionHandler markdownHandler = new SaveMarkdownActionHandler();
    private final SaveJsonActionHandler jsonHandler = new SaveJsonActionHandler();
    private final SaveExportedFileActionHandler exportedFileHandler = new SaveExportedFileActionHandler();

    @Test
    public void markdownActionIsSaveMarkdown() {
        assertEquals(UpstreamAction.SAVE_MARKDOWN, markdownHandler.action());
    }

    @Test
    public void markdownPayloadTypeIsString() {
        assertEquals(String.class, markdownHandler.payloadType());
    }

    @Test
    public void markdownActionValueMatchesLegacyStringType() {
        assertEquals("save_markdown", markdownHandler.action().value());
    }

    @Test
    public void jsonActionIsSaveJson() {
        assertEquals(UpstreamAction.SAVE_JSON, jsonHandler.action());
    }

    @Test
    public void jsonPayloadTypeIsString() {
        assertEquals(String.class, jsonHandler.payloadType());
    }

    @Test
    public void jsonActionValueMatchesLegacyStringType() {
        assertEquals("save_json", jsonHandler.action().value());
    }

    @Test
    public void exportedFileActionUsesTypedProtocol() {
        assertEquals(UpstreamAction.SAVE_EXPORTED_FILE, exportedFileHandler.action());
        assertEquals(String.class, exportedFileHandler.payloadType());
        assertEquals("save_exported_file", exportedFileHandler.action().value());
    }

    @Test
    public void exportedFilePersistenceRevalidatesBackendOwnedContract() throws Exception {
        String source = Files.readString(FILE_EXPORT_UTILS, StandardCharsets.UTF_8);

        assertTrue(source.contains("HistoryExportFormat.fromValue(formatValue)"));
        assertTrue(source.contains("format.matchesFileName(requestedFileName)"));
        assertTrue(source.contains("HistoryExportLimits.DEFAULT_MAX_UTF8_BYTES"));
        assertTrue(source.contains("HistoryExportPayloadField.CONTENT"));
        assertTrue(source.contains("HistoryExportPayloadField.FILE_NAME"));
        assertTrue(source.contains("HistoryExportPayloadField.FORMAT"));
        assertTrue(source.contains("DownstreamEvent.TOAST_SUCCESS.value()"));
        assertTrue(source.contains("DownstreamEvent.TOAST_ERROR.value()"));
        assertFalse(source.contains("window.addToast"));
        assertFalse(source.contains("executeJavaScriptOnEDT"));
    }

    @Test
    public void saveActionsRemainDistinct() {
        assertNotEquals(markdownHandler.action(), jsonHandler.action());
        assertNotEquals(markdownHandler.action(), exportedFileHandler.action());
        assertNotEquals(jsonHandler.action(), exportedFileHandler.action());
    }
}
