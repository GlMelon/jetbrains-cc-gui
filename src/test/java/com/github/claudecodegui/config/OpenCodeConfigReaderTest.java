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

    /**
     * opencode {@code --model} 要求 {@code provider/model} 格式(如 {@code openglm/glm-5.2});
     * 传裸名 {@code -m glm-5.2} 会触发 "Unexpected server error"——这正是 OpenCode CLI
     * 「Generating response 后无回复无错误」的根因之一(2026-06-29)。
     * <p>因此 registry 的 actualModel(CLI -m 透传值)必须带 provider 前缀;
     * 而 canonical id(选择键/去重)保持裸名,role 仍存 provider 名。
     */
    @Test
    public void readModelsProducesProviderSlashModelActualModel() throws Exception {
        Path cfg = writeConfig("{\"provider\":{\"openglm\":{\"name\":\"OpenGLM\","
                + "\"models\":{\"glm-5.2\":{\"name\":\"GLM 5.2\",\"limit\":{\"context\":131072}}}}}}");
        List<ModelConfig> models = OpenCodeConfigReader.readModels(cfg);
        assertEquals(1, models.size());
        ModelConfig m = models.get(0);
        assertEquals("glm-5.2", m.id());                  // canonical id 仍为裸名(选择键不变)
        assertEquals("openglm", m.role());                // role 存 provider 名
        assertEquals("openglm/glm-5.2", m.actualModel()); // actualModel 带 provider 前缀(CLI -m 透传)
        assertEquals(131072, m.contextWindow());
    }

    @Test
    public void readModelsNullPathReturnsEmpty() {
        List<ModelConfig> models = OpenCodeConfigReader.readModels((Path) null);
        assertTrue(models.isEmpty());
    }
}
