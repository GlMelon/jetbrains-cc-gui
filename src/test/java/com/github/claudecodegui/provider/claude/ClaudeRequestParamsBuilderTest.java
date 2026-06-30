package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.mcp.McpGatewaySdkBinding;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * buildSendParams 在 SDK Gateway 启用时把 binding 内联到 params.mcpGatewayBinding,
 * 由 Node 端 buildRequestContext 读取;不可用(未启用/未就绪/null)时完全省略,回退真实 MCP。
 */
public class ClaudeRequestParamsBuilderTest {
    private final Gson gson = new Gson();
    private final ClaudeRequestParamsBuilder builder = new ClaudeRequestParamsBuilder(gson);

    private JsonObject build(McpGatewaySdkBinding binding) {
        return builder.buildSendParams(
                "msg", "sid", "epoch", "/cwd", "default",
                "model", null, null, null, null, null, null, null, null, binding);
    }

    @Test
    public void addsMcpGatewayBindingWhenUsable() {
        McpGatewaySdkBinding binding = new McpGatewaySdkBinding(true, true, 5L,
                List.of("node", "client.js", "--revision", "5"), null);
        JsonObject params = build(binding);
        assertTrue(params.has("mcpGatewayBinding"));
        JsonObject serialized = params.getAsJsonObject("mcpGatewayBinding");
        assertTrue(serialized.get("enabled").getAsBoolean());
        assertTrue(serialized.get("ready").getAsBoolean());
        assertEquals(5L, serialized.get("revision").getAsLong());
        assertTrue(serialized.has("command"));
    }

    @Test
    public void omitsMcpGatewayBindingWhenNull() {
        assertFalse(build(null).has("mcpGatewayBinding"));
    }

    @Test
    public void omitsMcpGatewayBindingWhenDisabled() {
        assertFalse(build(McpGatewaySdkBinding.disabled("reason")).has("mcpGatewayBinding"));
    }
}
