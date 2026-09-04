package com.github.claudecodegui.provider.kimi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KimiMcpDiscoveryReader:wire.jsonl 的 mcp.tools_discovered 事件聚合
 * (多 server 多事件、无事件、畸形行、超大 tools 数组、目录/wire 缺失)。
 */
public class KimiMcpDiscoveryReaderTest {

    private Path newSession(Path base, String sessionId) throws IOException {
        Path dir = base.resolve("sessions").resolve("some-work-dir-key").resolve(sessionId);
        Files.createDirectories(dir.resolve("agents").resolve("main"));
        JsonObject state = new JsonObject();
        state.addProperty("title", "mcp demo");
        state.addProperty("workDir", "C:\\proj\\kdemo");
        Files.writeString(dir.resolve("state.json"), state.toString());
        return dir;
    }

    /** 程序化构造 tools_discovered 事件行(与实测 wire 形态一致),规避手写转义。 */
    private static String toolsDiscoveredEvent(String serverName, int toolCount) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "mcp.tools_discovered");
        event.addProperty("agentId", "main");
        event.addProperty("serverName", serverName);
        event.addProperty("hash", "abc123");
        JsonArray tools = new JsonArray();
        for (int i = 0; i < toolCount; i++) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", "tool_" + i);
            tool.addProperty("description", "demo");
            tool.add("inputSchema", new JsonObject());
            tools.add(tool);
        }
        event.add("tools", tools);
        return event.toString();
    }

    @Test
    public void aggregatesServersInDiscoveryOrderWithLastEventToolCount() throws IOException {
        Path base = Files.createTempDirectory("kimi-mcp");
        Path session = newSession(base, "session_01MCP1");

        StringBuilder wire = new StringBuilder();
        wire.append("{\"role\":\"user\",\"content\":\"hi\"}\n");
        wire.append(toolsDiscoveredEvent("melon_gateway", 5)).append('\n');
        wire.append(toolsDiscoveredEvent("filesystem", 3)).append('\n');
        // 同 server 重复事件:toolCount 取最后一条
        wire.append(toolsDiscoveredEvent("melon_gateway", 7)).append('\n');
        wire.append("junk line\n");
        // 含预过滤子串但 JSON 畸形 → 跳过不致死
        wire.append("{\"type\":\"mcp.tools_discovered\",\"serverName\":\n");
        // 缺 serverName 的事件 → 跳过
        wire.append("{\"type\":\"mcp.tools_discovered\",\"agentId\":\"main\",\"tools\":[]}\n");
        Files.writeString(session.resolve("agents").resolve("main").resolve("wire.jsonl"), wire.toString());

        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> servers =
                new KimiMcpDiscoveryReader(new KimiHistoryReader(base))
                        .readDiscoveredServers("session_01MCP1", "C:\\proj\\kdemo");

        assertEquals(2, servers.size());
        assertEquals("melon_gateway", servers.get(0).name());
        assertEquals(7, servers.get(0).toolCount());
        // toolNames 同样取最后一条事件
        assertEquals(7, servers.get(0).toolNames().size());
        assertEquals("tool_0", servers.get(0).toolNames().get(0));
        assertEquals("tool_6", servers.get(0).toolNames().get(6));
        assertEquals("filesystem", servers.get(1).name());
        assertEquals(3, servers.get(1).toolCount());
        assertEquals(List.of("tool_0", "tool_1", "tool_2"), servers.get(1).toolNames());
    }

    @Test
    public void sessionWithoutDiscoveryEventsYieldsEmptyList() throws IOException {
        Path base = Files.createTempDirectory("kimi-mcp2");
        Path session = newSession(base, "session_01MCP2");
        Files.writeString(session.resolve("agents").resolve("main").resolve("wire.jsonl"),
                "{\"role\":\"user\",\"content\":\"hi\"}\n");

        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> servers =
                new KimiMcpDiscoveryReader(new KimiHistoryReader(base))
                        .readDiscoveredServers("session_01MCP2", "C:\\proj\\kdemo");

        assertTrue(servers != null && servers.isEmpty());
    }

    @Test
    public void missingSessionDirOrWireYieldsNull() throws IOException {
        Path base = Files.createTempDirectory("kimi-mcp3");
        KimiMcpDiscoveryReader reader = new KimiMcpDiscoveryReader(new KimiHistoryReader(base));

        // 会话目录不存在
        assertNull(reader.readDiscoveredServers("session_404", "C:\\proj\\kdemo"));
        // sessionId 空白
        assertNull(reader.readDiscoveredServers(null, "C:\\proj\\kdemo"));
        assertNull(reader.readDiscoveredServers("  ", "C:\\proj\\kdemo"));

        // 会话在但 wire 缺失 → 不可读 → null
        newSession(base, "session_01MCP3");
        assertNull(reader.readDiscoveredServers("session_01MCP3", "C:\\proj\\kdemo"));
    }

    @Test
    public void parseWireHandlesLargeToolsArray() throws IOException {
        Path base = Files.createTempDirectory("kimi-mcp4");
        Path wire = base.resolve("wire.jsonl");
        Files.writeString(wire, toolsDiscoveredEvent("big_server", 2000) + "\n");

        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> servers = KimiMcpDiscoveryReader.parseWire(wire);

        assertEquals(1, servers.size());
        assertEquals("big_server", servers.get(0).name());
        assertEquals(2000, servers.get(0).toolCount());
        assertEquals(2000, servers.get(0).toolNames().size());
    }

    @Test
    public void nonStringToolNamesSkippedButStillCounted() throws IOException {
        Path base = Files.createTempDirectory("kimi-mcp5");
        Path wire = base.resolve("wire.jsonl");
        JsonObject event = new JsonObject();
        event.addProperty("type", "mcp.tools_discovered");
        event.addProperty("serverName", "mixed");
        JsonArray tools = new JsonArray();
        JsonObject ok = new JsonObject();
        ok.addProperty("name", "good_tool");
        tools.add(ok);
        JsonObject numericName = new JsonObject();
        numericName.addProperty("name", 42);
        tools.add(numericName);
        tools.add(new JsonObject()); // 缺 name
        tools.add("plain-string");   // 非对象元素
        event.add("tools", tools);
        Files.writeString(wire, event.toString() + "\n");

        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> servers = KimiMcpDiscoveryReader.parseWire(wire);

        assertEquals(1, servers.size());
        // toolCount 仍是 tools 数组长度;toolNames 只收字符串 name
        assertEquals(4, servers.get(0).toolCount());
        assertEquals(List.of("good_tool"), servers.get(0).toolNames());
    }
}
