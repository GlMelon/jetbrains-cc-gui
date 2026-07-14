package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

public class McpServerActionHandlerTest {

    private final McpServerActionHandlers handlers = null;

    @Test
    public void getMcpServersAction_matchesLegacyType() {
        GetMcpServersActionHandler h = new GetMcpServersActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.GET_MCP_SERVERS, h.action());
        Assert.assertEquals("get_mcp_servers", h.action().value());
    }

    @Test
    public void getMcpServerStatusAction_matchesLegacyType() {
        GetMcpServerStatusActionHandler h = new GetMcpServerStatusActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.GET_MCP_SERVER_STATUS, h.action());
        Assert.assertEquals("get_mcp_server_status", h.action().value());
    }

    @Test
    public void getMcpServerToolsAction_matchesLegacyType() {
        GetMcpServerToolsActionHandler h = new GetMcpServerToolsActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.GET_MCP_SERVER_TOOLS, h.action());
        Assert.assertEquals("get_mcp_server_tools", h.action().value());
    }

    @Test
    public void addMcpServerAction_matchesLegacyType() {
        AddMcpServerActionHandler h = new AddMcpServerActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.ADD_MCP_SERVER, h.action());
        Assert.assertEquals("add_mcp_server", h.action().value());
    }

    @Test
    public void updateMcpServerAction_matchesLegacyType() {
        UpdateMcpServerActionHandler h = new UpdateMcpServerActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.UPDATE_MCP_SERVER, h.action());
        Assert.assertEquals("update_mcp_server", h.action().value());
    }

    @Test
    public void deleteMcpServerAction_matchesLegacyType() {
        DeleteMcpServerActionHandler h = new DeleteMcpServerActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.DELETE_MCP_SERVER, h.action());
        Assert.assertEquals("delete_mcp_server", h.action().value());
    }

    @Test
    public void toggleMcpServerAction_matchesLegacyType() {
        ToggleMcpServerActionHandler h = new ToggleMcpServerActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.TOGGLE_MCP_SERVER, h.action());
        Assert.assertEquals("toggle_mcp_server", h.action().value());
    }

    @Test
    public void validateMcpServerAction_matchesLegacyType() {
        ValidateMcpServerActionHandler h = new ValidateMcpServerActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.VALIDATE_MCP_SERVER, h.action());
        Assert.assertEquals("validate_mcp_server", h.action().value());
    }

    @Test
    public void allImplementFrontendActionHandler() {
        McpServerActionHandlers dummy = null;
        FrontendActionHandler<?>[] handlers = {
            new GetMcpServersActionHandler(dummy),
            new GetMcpServerStatusActionHandler(dummy),
            new GetMcpServerToolsActionHandler(dummy),
            new AddMcpServerActionHandler(dummy),
            new UpdateMcpServerActionHandler(dummy),
            new DeleteMcpServerActionHandler(dummy),
            new ToggleMcpServerActionHandler(dummy),
            new ValidateMcpServerActionHandler(dummy),
        };
        for (FrontendActionHandler<?> h : handlers) {
            Assert.assertNotNull("action() must not be null", h.action());
            Assert.assertNotNull("payloadType() must not be null", h.payloadType());
        }
    }

    @Test
    public void payloadTypes_areAllString() {
        McpServerActionHandlers dummy = null;
        Assert.assertEquals(String.class, new GetMcpServersActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new GetMcpServerStatusActionHandler(dummy).payloadType());
        Assert.assertEquals(McpServerToolsRequest.class, new GetMcpServerToolsActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new AddMcpServerActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new UpdateMcpServerActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new DeleteMcpServerActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new ToggleMcpServerActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new ValidateMcpServerActionHandler(dummy).payloadType());
    }

    @Test
    public void actions_areDistinct() {
        McpServerActionHandlers dummy = null;
        java.util.Set<UpstreamAction> seen = new java.util.HashSet<>();
        for (FrontendActionHandler<?> h : new FrontendActionHandler<?>[]{
            new GetMcpServersActionHandler(dummy),
            new GetMcpServerStatusActionHandler(dummy),
            new GetMcpServerToolsActionHandler(dummy),
            new AddMcpServerActionHandler(dummy),
            new UpdateMcpServerActionHandler(dummy),
            new DeleteMcpServerActionHandler(dummy),
            new ToggleMcpServerActionHandler(dummy),
            new ValidateMcpServerActionHandler(dummy),
        }) {
            Assert.assertTrue("Duplicate action: " + h.action(), seen.add(h.action()));
        }
        Assert.assertEquals(8, seen.size());
    }
}
