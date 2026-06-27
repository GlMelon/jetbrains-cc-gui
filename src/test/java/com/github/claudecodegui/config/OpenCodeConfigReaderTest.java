package com.github.claudecodegui.config;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * §15.9 B22:OpenCode MCP server 配置读取测试。
 * <p>OpenCode MCP 工具在会话中按需透传(message.part.updated type=tool → tool_use,见 event-mapper),
 * opencode 无"列工具"命令/SDK API;readMcpServers 仅暴露 server 配置让"配置了哪些 server"可达。
 */
public class OpenCodeConfigReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path writeConfig(String json) throws Exception {
        File file = tmp.newFile("opencode.json");
        Files.writeString(file.toPath(), json);
        return file.toPath();
    }

    @Test
    public void readMcpServersParsesLocalServer() throws Exception {
        Path cfg = writeConfig("{\"mcp\":{\"gen\":{\"type\":\"local\",\"command\":[\"npx\",\"pkg\"],\"enabled\":true}}}");
        List<JsonObject> servers = OpenCodeConfigReader.readMcpServers(cfg);
        assertEquals(1, servers.size());
        JsonObject s = servers.get(0);
        assertEquals("gen", s.get("id").getAsString());
        assertEquals("local", s.get("type").getAsString());
        assertTrue(s.get("enabled").getAsBoolean());
        assertNotNull(s.get("command"));
    }

    @Test
    public void readMcpServersParsesRemoteServer() throws Exception {
        Path cfg = writeConfig("{\"mcp\":{\"srv\":{\"type\":\"remote\",\"url\":\"https://x/api\",\"enabled\":true}}}");
        List<JsonObject> servers = OpenCodeConfigReader.readMcpServers(cfg);
        assertEquals(1, servers.size());
        JsonObject s = servers.get(0);
        assertEquals("remote", s.get("type").getAsString());
        assertEquals("https://x/api", s.get("url").getAsString());
    }

    @Test
    public void readMcpServersEmptyWhenNoMcpField() throws Exception {
        Path cfg = writeConfig("{\"provider\":{}}");
        List<JsonObject> servers = OpenCodeConfigReader.readMcpServers(cfg);
        assertTrue(servers.isEmpty());
    }

    @Test
    public void readMcpServersDefaultsEnabledTrueWhenMissing() throws Exception {
        Path cfg = writeConfig("{\"mcp\":{\"s\":{\"type\":\"local\",\"command\":[\"x\"]}}}");
        List<JsonObject> servers = OpenCodeConfigReader.readMcpServers(cfg);
        assertEquals(1, servers.size());
        assertTrue(servers.get(0).get("enabled").getAsBoolean());
    }

    @Test
    public void readMcpServersNullPathReturnsEmpty() {
        List<JsonObject> servers = OpenCodeConfigReader.readMcpServers((Path) null);
        assertTrue(servers.isEmpty());
    }
}
