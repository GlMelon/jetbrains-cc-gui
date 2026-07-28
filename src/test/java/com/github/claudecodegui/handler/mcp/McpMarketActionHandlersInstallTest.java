package com.github.claudecodegui.handler.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * McpMarketActionHandlers 一键安装风险校验单测(纯函数 {@link McpMarketActionHandlers#evaluateInstallRisk})。
 *
 * <p>核心安全逻辑:riskLevel={@code unverified-command} 后端拒绝(marketplace 不可信 runner / 危险 flag),
 * 前端虽有风险警告但不可绕过此后端闸门。异步 dispatch/invokeLater 链路留端到端 runIde 验证。
 */
public class McpMarketActionHandlersInstallTest {

    /** 构造一个带指定 riskLevel 的完整 server(id + server spec)。riskLevel=null 表示不写该字段。 */
    private static JsonObject serverWithRisk(String riskLevel) {
        JsonObject spec = new JsonObject();
        if (riskLevel != null) {
            spec.addProperty("riskLevel", riskLevel);
        }
        spec.addProperty("type", "stdio");
        spec.addProperty("command", "npx");
        JsonObject server = new JsonObject();
        server.addProperty("id", "test-server");
        server.add("server", spec);
        return server;
    }

    @Test
    public void rejectsUnverifiedCommand() {
        // marketplace 不可信 runner / 危险 flag:后端硬拒绝,不给前端绕过。
        assertEquals("INSTALL_REJECTED_RISK",
            McpMarketActionHandlers.evaluateInstallRisk(serverWithRisk("unverified-command")));
    }

    @Test
    public void allowsLocalCommand() {
        assertNull(McpMarketActionHandlers.evaluateInstallRisk(serverWithRisk("local-command")));
    }

    @Test
    public void allowsContainerCommand() {
        assertNull(McpMarketActionHandlers.evaluateInstallRisk(serverWithRisk("container-command")));
    }

    @Test
    public void allowsRemote() {
        assertNull(McpMarketActionHandlers.evaluateInstallRisk(serverWithRisk("remote")));
    }

    @Test
    public void allowsMissingRiskLevel() {
        // 旧/手编 server 无 riskLevel:允许(非市场来源,无风险分级信息)。
        assertNull(McpMarketActionHandlers.evaluateInstallRisk(serverWithRisk(null)));
    }

    @Test
    public void rejectsMissingServerSpec() {
        JsonObject noSpec = new JsonObject();
        noSpec.addProperty("id", "test");
        assertEquals("INVALID_INSTALL_OPTION",
            McpMarketActionHandlers.evaluateInstallRisk(noSpec));
    }

    @Test
    public void rejectsNullServer() {
        assertEquals("INVALID_INSTALL_OPTION",
            McpMarketActionHandlers.evaluateInstallRisk(null));
    }
}
