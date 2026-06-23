package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for diff action handlers (B2 迁移, DiffHandler).
 * Verifies action/payloadType contract only — business logic lives in the
 * DiffRequestDispatcher sub-handlers reached via DiffActionHandlers.
 */
public class DiffActionHandlerContractTest {

    @Test
    public void testRefreshFileActionContract() {
        RefreshFileActionHandler h = new RefreshFileActionHandler(null);
        Assert.assertEquals(UpstreamAction.REFRESH_FILE, h.action());
        Assert.assertEquals("refresh_file", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
        Assert.assertTrue(h instanceof FrontendActionHandler<?>);
    }

    @Test
    public void testShowDiffActionContract() {
        ShowDiffActionHandler h = new ShowDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_DIFF, h.action());
        Assert.assertEquals("show_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testShowMultiEditDiffActionContract() {
        ShowMultiEditDiffActionHandler h = new ShowMultiEditDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_MULTI_EDIT_DIFF, h.action());
        Assert.assertEquals("show_multi_edit_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testShowEditPreviewDiffActionContract() {
        ShowEditPreviewDiffActionHandler h = new ShowEditPreviewDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_EDIT_PREVIEW_DIFF, h.action());
        Assert.assertEquals("show_edit_preview_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testShowEditFullDiffActionContract() {
        ShowEditFullDiffActionHandler h = new ShowEditFullDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_EDIT_FULL_DIFF, h.action());
        Assert.assertEquals("show_edit_full_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testShowEditableDiffActionContract() {
        ShowEditableDiffActionHandler h = new ShowEditableDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_EDITABLE_DIFF, h.action());
        Assert.assertEquals("show_editable_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testShowInteractiveDiffActionContract() {
        ShowInteractiveDiffActionHandler h = new ShowInteractiveDiffActionHandler(null);
        Assert.assertEquals(UpstreamAction.SHOW_INTERACTIVE_DIFF, h.action());
        Assert.assertEquals("show_interactive_diff", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
