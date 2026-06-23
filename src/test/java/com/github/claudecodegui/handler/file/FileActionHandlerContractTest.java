package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for file action handlers (B2 迁移, FileHandler).
 * Verifies action/payloadType contract only — business logic lives in FileActionHandlers.
 */
public class FileActionHandlerContractTest {

    @Test
    public void testListFilesActionContract() {
        ListFilesActionHandler h = new ListFilesActionHandler(null);
        Assert.assertEquals(UpstreamAction.LIST_FILES, h.action());
        Assert.assertEquals("list_files", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
        Assert.assertTrue(h instanceof FrontendActionHandler<?>);
    }

    @Test
    public void testOpenFileActionContract() {
        OpenFileActionHandler h = new OpenFileActionHandler(null);
        Assert.assertEquals(UpstreamAction.OPEN_FILE, h.action());
        Assert.assertEquals("open_file", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testOpenBrowserActionContract() {
        OpenBrowserActionHandler h = new OpenBrowserActionHandler(null);
        Assert.assertEquals(UpstreamAction.OPEN_BROWSER, h.action());
        Assert.assertEquals("open_browser", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testOpenClassActionContract() {
        OpenClassActionHandler h = new OpenClassActionHandler(null);
        Assert.assertEquals(UpstreamAction.OPEN_CLASS, h.action());
        Assert.assertEquals("open_class", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetLinkifyCapabilitiesActionContract() {
        GetLinkifyCapabilitiesActionHandler h = new GetLinkifyCapabilitiesActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_LINKIFY_CAPABILITIES, h.action());
        Assert.assertEquals("get_linkify_capabilities", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testResolveFilePathActionContract() {
        ResolveFilePathActionHandler h = new ResolveFilePathActionHandler(null);
        Assert.assertEquals(UpstreamAction.RESOLVE_FILE_PATH, h.action());
        Assert.assertEquals("resolve_file_path", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
