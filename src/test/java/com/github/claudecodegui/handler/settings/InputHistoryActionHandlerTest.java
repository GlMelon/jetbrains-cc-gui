package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/** Contract tests for input-history action handlers (B3 slice: input history). */
public class InputHistoryActionHandlerTest {

    private void assertContract(FrontendActionHandler<String> h, UpstreamAction expected, String value) {
        Assert.assertEquals(expected, h.action());
        Assert.assertEquals(value, h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetInputHistory() {
        assertContract(new GetInputHistoryActionHandler(null), UpstreamAction.GET_INPUT_HISTORY, "get_input_history");
    }

    @Test
    public void testRecordInputHistory() {
        assertContract(new RecordInputHistoryActionHandler(null), UpstreamAction.RECORD_INPUT_HISTORY, "record_input_history");
    }

    @Test
    public void testDeleteInputHistoryItem() {
        assertContract(new DeleteInputHistoryItemActionHandler(null), UpstreamAction.DELETE_INPUT_HISTORY_ITEM, "delete_input_history_item");
    }

    @Test
    public void testClearInputHistory() {
        assertContract(new ClearInputHistoryActionHandler(null), UpstreamAction.CLEAR_INPUT_HISTORY, "clear_input_history");
    }

    @Test
    public void testImplementsFrontendActionHandler() {
        Assert.assertTrue(new GetInputHistoryActionHandler(null) instanceof FrontendActionHandler<?>);
    }
}
