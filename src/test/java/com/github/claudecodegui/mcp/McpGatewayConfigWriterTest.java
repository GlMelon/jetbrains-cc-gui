package com.github.claudecodegui.mcp;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 修复④:gateway 路径下临时 CODEX_HOME 的 config.toml 必须复制真实 ~/.codex/config.toml 的稳定段
 * (model/model_provider/model_reasoning_effort/[model_providers.*]/proxy 等),
 * 否则 codex 用默认 model(自定义 provider 502/卡死)+ 无 reasoning → 思考内容丢失。
 */
public class McpGatewayConfigWriterTest {

    @Test
    public void copyCodexStableSectionsPreservesModelAndProvidersExcludesMcpServers() throws Exception {
        Path tmp = Files.createTempDirectory("codex-config-test");
        Path config = tmp.resolve("config.toml");
        Files.writeString(config,
                "model = \"gpt-5.5\"\n"
                        + "model_provider = \"custom\"\n"
                        + "model_reasoning_effort = \"high\"\n"
                        + "\n[model_providers.custom]\n"
                        + "name = \"Galaxy\"\n"
                        + "base_url = \"https://example.com/v1\"\n"
                        + "\n[mcp_servers.foo]\n"
                        + "command = \"npx\"\n"
                        + "args = [\"foo-mcp\"]\n",
                StandardCharsets.UTF_8);

        String result = McpGatewayConfigWriter.copyCodexStableSections(config);

        // 稳定段必须保留(否则 codex 默认 model + 无 reasoning)
        assertTrue("model 必须保留", result.contains("model = \"gpt-5.5\""));
        assertTrue("model_reasoning_effort 必须保留(否则 codex 思考丢失)",
                result.contains("model_reasoning_effort = \"high\""));
        assertTrue("[model_providers.*] 必须保留(自定义 provider)", result.contains("[model_providers.custom]"));
        assertTrue("model_provider 顶层键必须保留", result.contains("model_provider = \"custom\""));

        // mcp_servers 段由 gateway 聚合提供,必须剥离(避免与 gateway 段重复)
        assertFalse("[mcp_servers.*] 必须剥离", result.contains("[mcp_servers"));
        assertFalse("原 mcp server 命令必须剥离", result.contains("npx"));
        assertFalse("原 mcp server 名必须剥离", result.contains("foo-mcp"));
    }

    @Test
    public void copyCodexStableSectionsPreservesMcpServerEntriesFollowingOtherSections() throws Exception {
        // 验证跳过逻辑在遇到下一个非 mcp_servers 段时正确恢复(不误剥后续段)
        Path tmp = Files.createTempDirectory("codex-config-test");
        Path config = tmp.resolve("config.toml");
        Files.writeString(config,
                "model_reasoning_effort = \"medium\"\n"
                        + "\n[mcp_servers.foo]\n"
                        + "command = \"npx\"\n"
                        + "\n[history]\n"
                        + "persistence = \"save-all\"\n",
                StandardCharsets.UTF_8);

        String result = McpGatewayConfigWriter.copyCodexStableSections(config);

        assertTrue("mcp_servers 后的 [history] 段必须保留", result.contains("[history]"));
        assertTrue(result.contains("persistence = \"save-all\""));
        assertFalse("[mcp_servers.*] 必须剥离", result.contains("[mcp_servers"));
        assertTrue("model_reasoning_effort 必须保留", result.contains("model_reasoning_effort = \"medium\""));
    }

    @Test
    public void copyCodexStableSectionsReturnsEmptyWhenMissing() {
        // 源不存在 / 入参 null → 空串(由调用方仅写 gateway 段)
        Path tmp;
        try {
            tmp = Files.createTempDirectory("codex-config-test");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals("", McpGatewayConfigWriter.copyCodexStableSections(tmp.resolve("nonexistent.toml")));
        assertEquals("", McpGatewayConfigWriter.copyCodexStableSections(null));
    }
}
