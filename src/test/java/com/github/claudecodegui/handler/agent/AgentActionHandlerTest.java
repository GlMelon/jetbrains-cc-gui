package com.github.claudecodegui.handler.agent;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for agent action handlers.
 * Verifies action/payloadType contract only — business logic lives in AgentActionHandlers.
 */
public class AgentActionHandlerTest {

    @Test
    public void testGetAgentsActionContract() {
        GetAgentsActionHandler h = new GetAgentsActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_AGENTS, h.action());
        Assert.assertEquals("get_agents", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testAddAgentActionContract() {
        AddAgentActionHandler h = new AddAgentActionHandler(null);
        Assert.assertEquals(UpstreamAction.ADD_AGENT, h.action());
        Assert.assertEquals("add_agent", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testUpdateAgentActionContract() {
        UpdateAgentActionHandler h = new UpdateAgentActionHandler(null);
        Assert.assertEquals(UpstreamAction.UPDATE_AGENT, h.action());
        Assert.assertEquals("update_agent", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testDeleteAgentActionContract() {
        DeleteAgentActionHandler h = new DeleteAgentActionHandler(null);
        Assert.assertEquals(UpstreamAction.DELETE_AGENT, h.action());
        Assert.assertEquals("delete_agent", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetSelectedAgentActionContract() {
        GetSelectedAgentActionHandler h = new GetSelectedAgentActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_SELECTED_AGENT, h.action());
        Assert.assertEquals("get_selected_agent", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testSetSelectedAgentActionContract() {
        SetSelectedAgentActionHandler h = new SetSelectedAgentActionHandler(null);
        Assert.assertEquals(UpstreamAction.SET_SELECTED_AGENT, h.action());
        Assert.assertEquals("set_selected_agent", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testExportAgentsActionContract() {
        ExportAgentsActionHandler h = new ExportAgentsActionHandler(null);
        Assert.assertEquals(UpstreamAction.EXPORT_AGENTS, h.action());
        Assert.assertEquals("export_agents", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testImportAgentsFileActionContract() {
        ImportAgentsFileActionHandler h = new ImportAgentsFileActionHandler(null);
        Assert.assertEquals(UpstreamAction.IMPORT_AGENTS_FILE, h.action());
        Assert.assertEquals("import_agents_file", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testSaveImportedAgentsActionContract() {
        SaveImportedAgentsActionHandler h = new SaveImportedAgentsActionHandler(null);
        Assert.assertEquals(UpstreamAction.SAVE_IMPORTED_AGENTS, h.action());
        Assert.assertEquals("save_imported_agents", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
