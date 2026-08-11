package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for node process action handlers.
 * Verifies action/payloadType contract only — business logic lives in NodeProcessActionHandlers.
 */
public class NodeProcessActionHandlerTest {

    @Test
    public void testGetNodeProcessesActionContract() {
        GetNodeProcessesActionHandler h = new GetNodeProcessesActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_NODE_PROCESSES, h.action());
        Assert.assertEquals("get_node_processes", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
        Assert.assertTrue(h instanceof FrontendActionHandler<?>);
    }

    @Test
    public void testKillNodeProcessActionContract() {
        KillNodeProcessActionHandler h = new KillNodeProcessActionHandler(null);
        Assert.assertEquals(UpstreamAction.KILL_NODE_PROCESS, h.action());
        Assert.assertEquals("kill_node_process", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testKillAllOrphansActionContract() {
        KillAllOrphansActionHandler h = new KillAllOrphansActionHandler(null);
        Assert.assertEquals(UpstreamAction.KILL_ALL_ORPHANS, h.action());
        Assert.assertEquals("kill_all_orphans", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
