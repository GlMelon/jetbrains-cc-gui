package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.*;

public class UndoFileActionHandlerTest {

    private final UndoFileChangesActionHandler singleHandler = new UndoFileChangesActionHandler();
    private final UndoAllFileChangesActionHandler batchHandler = new UndoAllFileChangesActionHandler();

    // ── UndoFileChangesActionHandler ──

    @Test
    public void singleActionIsUndoFileChanges() {
        assertEquals(UpstreamAction.UNDO_FILE_CHANGES, singleHandler.action());
    }

    @Test
    public void singlePayloadTypeIsString() {
        assertEquals(String.class, singleHandler.payloadType());
    }

    @Test
    public void singleActionValueMatchesLegacyStringType() {
        assertEquals("undo_file_changes", singleHandler.action().value());
    }

    // ── UndoAllFileChangesActionHandler ──

    @Test
    public void batchActionIsUndoAllFileChanges() {
        assertEquals(UpstreamAction.UNDO_ALL_FILE_CHANGES, batchHandler.action());
    }

    @Test
    public void batchPayloadTypeIsString() {
        assertEquals(String.class, batchHandler.payloadType());
    }

    @Test
    public void batchActionValueMatchesLegacyStringType() {
        assertEquals("undo_all_file_changes", batchHandler.action().value());
    }

    // ── Distinct actions ──

    @Test
    public void singleAndBatchAreDistinctActions() {
        assertNotEquals(singleHandler.action(), batchHandler.action());
    }
}
