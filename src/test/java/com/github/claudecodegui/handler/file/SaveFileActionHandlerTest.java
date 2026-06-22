package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.*;

public class SaveFileActionHandlerTest {

    private final SaveMarkdownActionHandler markdownHandler = new SaveMarkdownActionHandler();
    private final SaveJsonActionHandler jsonHandler = new SaveJsonActionHandler();

    // ── SaveMarkdownActionHandler ──

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

    // ── SaveJsonActionHandler ──

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

    // ── Distinct actions ──

    @Test
    public void markdownAndJsonAreDistinctActions() {
        assertNotEquals(markdownHandler.action(), jsonHandler.action());
    }
}
