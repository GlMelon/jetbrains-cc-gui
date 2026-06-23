package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for Codex MCP server action handlers.
 * Verifies action/payloadType contract only — business logic lives in CodexMcpServerActionHandlers.
 */
public class CodexMcpServerActionHandlerTest {

    @Test
    public void testGetCodexMcpServersActionContract() {
        GetCodexMcpServersActionHandler h = new GetCodexMcpServersActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_CODEX_MCP_SERVERS, h.action());
        Assert.assertEquals("get_codex_mcp_servers", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetCodexMcpServerStatusActionContract() {
        GetCodexMcpServerStatusActionHandler h = new GetCodexMcpServerStatusActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_CODEX_MCP_SERVER_STATUS, h.action());
        Assert.assertEquals("get_codex_mcp_server_status", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testGetCodexMcpServerToolsActionContract() {
        GetCodexMcpServerToolsActionHandler h = new GetCodexMcpServerToolsActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_CODEX_MCP_SERVER_TOOLS, h.action());
        Assert.assertEquals("get_codex_mcp_server_tools", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testAddCodexMcpServerActionContract() {
        AddCodexMcpServerActionHandler h = new AddCodexMcpServerActionHandler(null);
        Assert.assertEquals(UpstreamAction.ADD_CODEX_MCP_SERVER, h.action());
        Assert.assertEquals("add_codex_mcp_server", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testUpdateCodexMcpServerActionContract() {
        UpdateCodexMcpServerActionHandler h = new UpdateCodexMcpServerActionHandler(null);
        Assert.assertEquals(UpstreamAction.UPDATE_CODEX_MCP_SERVER, h.action());
        Assert.assertEquals("update_codex_mcp_server", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testDeleteCodexMcpServerActionContract() {
        DeleteCodexMcpServerActionHandler h = new DeleteCodexMcpServerActionHandler(null);
        Assert.assertEquals(UpstreamAction.DELETE_CODEX_MCP_SERVER, h.action());
        Assert.assertEquals("delete_codex_mcp_server", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testToggleCodexMcpServerActionContract() {
        ToggleCodexMcpServerActionHandler h = new ToggleCodexMcpServerActionHandler(null);
        Assert.assertEquals(UpstreamAction.TOGGLE_CODEX_MCP_SERVER, h.action());
        Assert.assertEquals("toggle_codex_mcp_server", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testValidateCodexMcpServerActionContract() {
        ValidateCodexMcpServerActionHandler h = new ValidateCodexMcpServerActionHandler(null);
        Assert.assertEquals(UpstreamAction.VALIDATE_CODEX_MCP_SERVER, h.action());
        Assert.assertEquals("validate_codex_mcp_server", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
