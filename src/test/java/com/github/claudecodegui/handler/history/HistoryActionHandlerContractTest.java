package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for history action handlers (B2/B4 迁移, HistoryHandler).
 * Verifies action/payloadType contract only — business logic lives in the
 * seven history service collaborators reached via HistoryActionHandlers.
 */
public class HistoryActionHandlerContractTest {

    private void assertContract(FrontendActionHandler<String> h, UpstreamAction expected, String value) {
        Assert.assertEquals(expected, h.action());
        Assert.assertEquals(value, h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test public void testLoadHistoryData() {
        assertContract(new LoadHistoryDataActionHandler(null), UpstreamAction.LOAD_HISTORY_DATA, "load_history_data");
    }

    @Test public void testLoadSession() {
        assertContract(new LoadSessionActionHandler(null), UpstreamAction.LOAD_SESSION, "load_session");
    }

    @Test public void testDeleteSession() {
        assertContract(new DeleteSessionActionHandler(null), UpstreamAction.DELETE_SESSION, "delete_session");
    }

    @Test public void testDeleteSessions() {
        assertContract(new DeleteSessionsActionHandler(null), UpstreamAction.DELETE_SESSIONS, "delete_sessions");
    }

    @Test public void testArchiveSessions() {
        assertContract(new ArchiveSessionsActionHandler(null), UpstreamAction.ARCHIVE_SESSIONS, "archive_sessions");
    }

    @Test public void testExportSession() {
        assertContract(new ExportSessionActionHandler(null), UpstreamAction.EXPORT_SESSION, "export_session");
    }

    @Test public void testToggleFavorite() {
        assertContract(new ToggleFavoriteActionHandler(null), UpstreamAction.TOGGLE_FAVORITE, "toggle_favorite");
    }

    @Test public void testUpdateTitle() {
        assertContract(new UpdateTitleActionHandler(null), UpstreamAction.UPDATE_TITLE, "update_title");
    }

    @Test public void testDeleteTitle() {
        assertContract(new DeleteTitleActionHandler(null), UpstreamAction.DELETE_TITLE, "delete_title");
    }

    @Test public void testDeepSearchHistory() {
        assertContract(new DeepSearchHistoryActionHandler(null), UpstreamAction.DEEP_SEARCH_HISTORY, "deep_search_history");
    }

    @Test public void testLoadSubagentSession() {
        assertContract(new LoadSubagentSessionActionHandler(null), UpstreamAction.LOAD_SUBAGENT_SESSION, "load_subagent_session");
    }

    @Test public void testConvertToCliSession() {
        assertContract(new ConvertToCliSessionActionHandler(null), UpstreamAction.CONVERT_TO_CLI_SESSION, "convert_to_cli_session");
    }

    @Test public void testImplementsFrontendActionHandler() {
        Assert.assertTrue(new LoadHistoryDataActionHandler(null) instanceof FrontendActionHandler<?>);
    }
}
