package com.github.claudecodegui.mcp;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class McpGatewayConfigSnapshotTest {
    @Test
    public void hashDoesNotIncludeRevision() {
        JsonObject config = new JsonObject();
        config.addProperty(McpGatewayConstants.KEY_COMMAND, "npx");
        McpGatewayServerSpec server = new McpGatewayServerSpec(
                CommonConstants.PROVIDER_CLAUDE,
                "idea_mcp",
                true,
                McpGatewayConstants.TRANSPORT_STDIO,
                config
        );

        McpGatewayConfigSnapshot first = McpGatewayConfigSnapshot.create(1L, "D:/project", List.of(server));
        McpGatewayConfigSnapshot second = McpGatewayConfigSnapshot.create(2L, "D:/project", List.of(server));

        assertEquals(first.configHash(), second.configHash());
        assertFalse(first.configHash().isBlank());
    }
}
