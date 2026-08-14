package com.github.claudecodegui.settings;

import com.github.claudecodegui.mcp.McpInstallRejectedException;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link OpenCodeSettingsManager} MCP 增删改单元测试:外科手术式写 mcp 段
 * (保留 provider/permission 等其他段)、前端嵌套形状→原生条目转换、
 * SEC-01 闸门拒绝不落盘、解析失败拒写(不清空重建)。
 */
public class OpenCodeSettingsManagerMcpTest {

    private final Gson gson = new Gson();
    private OpenCodeSettingsManager manager;
    private Path tempHome;
    private String originalHomeDir;

    @Before
    public void setUp() throws Exception {
        tempHome = Files.createTempDirectory("opencode-mcp-home");
        useTemporaryHome(tempHome);
        manager = new OpenCodeSettingsManager(gson);
    }

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
        }
        deleteRecursively(tempHome);
    }

    // ==================== upsert ====================

    @Test
    public void upsertAddsServerAndPreservesOtherSections() throws Exception {
        seedConfigWithExistingSections();

        manager.upsertMcpServer(frontendServer("new-server", "npx", args("-y", "@scope/server")));

        JsonObject root = readConfigFile();
        // mcp 段:新条目为原生形状(command 数组 + environment + type local)
        JsonObject entry = root.getAsJsonObject("mcp").getAsJsonObject("new-server");
        assertNotNull(entry);
        assertEquals("local", entry.get("type").getAsString());
        JsonArray command = entry.getAsJsonArray("command");
        assertEquals(3, command.size());
        assertEquals("npx", command.get(0).getAsString());
        assertEquals("-y", command.get(1).getAsString());
        assertEquals("@scope/server", command.get(2).getAsString());
        assertEquals("v", entry.getAsJsonObject("environment").get("K").getAsString());
        assertTrue(entry.get("enabled").getAsBoolean());
        // 既有 mcp 条目原样保留
        assertEquals("existing", root.getAsJsonObject("mcp").getAsJsonObject("idea_mcp").get("tag").getAsString());
        // 其他段原样保留
        assertEquals("keep", root.getAsJsonObject("provider").get("p").getAsString());
        assertEquals("keep", root.getAsJsonObject("permission").get("x").getAsString());
    }

    @Test
    public void upsertUpdatesSameId() throws Exception {
        seedConfigWithExistingSections();

        manager.upsertMcpServer(frontendServer("idea_mcp", "node", args("server.js")));
        manager.upsertMcpServer(frontendServer("idea_mcp", "node", args("server2.js")));

        JsonObject mcp = readConfigFile().getAsJsonObject("mcp");
        assertEquals(1, mcp.size());
        assertEquals("server2.js", mcp.getAsJsonObject("idea_mcp").getAsJsonArray("command").get(1).getAsString());
    }

    @Test
    public void upsertRemoteServerFoldsToRemoteEntry() throws Exception {
        JsonObject server = new JsonObject();
        server.addProperty("id", "remote-srv");
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "http");
        spec.addProperty("url", "https://mcp.example.com/sse");
        server.add("server", spec);
        server.addProperty("enabled", false);

        manager.upsertMcpServer(server);

        JsonObject entry = readConfigFile().getAsJsonObject("mcp").getAsJsonObject("remote-srv");
        assertEquals("remote", entry.get("type").getAsString());
        assertEquals("https://mcp.example.com/sse", entry.get("url").getAsString());
        assertFalse(entry.get("enabled").getAsBoolean());
        assertNull(entry.get("command"));
    }

    @Test
    public void upsertOnMissingFileCreatesFile() throws Exception {
        // 不预置 opencode.json:目录与文件按需创建
        manager.upsertMcpServer(frontendServer("first", "npx", args("-y", "pkg")));

        JsonObject root = readConfigFile();
        assertEquals(1, root.getAsJsonObject("mcp").size());
    }

    // ==================== delete ====================

    @Test
    public void deleteExistingServerRemovesEntry() throws Exception {
        seedConfigWithExistingSections();

        assertTrue(manager.deleteMcpServer("idea_mcp"));

        JsonObject root = readConfigFile();
        assertFalse(root.getAsJsonObject("mcp").has("idea_mcp"));
        // 其他段原样保留
        assertEquals("keep", root.getAsJsonObject("provider").get("p").getAsString());
    }

    @Test
    public void deleteMissingServerReturnsFalseAndKeepsFile() throws Exception {
        seedConfigWithExistingSections();

        // 不存在的 key:返回 false 且文件不动
        assertFalse(manager.deleteMcpServer("no-such-server"));
        assertEquals("existing", readConfigFile().getAsJsonObject("mcp").getAsJsonObject("idea_mcp").get("tag").getAsString());
        // 删除后重复删除也返回 false
        assertTrue(manager.deleteMcpServer("idea_mcp"));
        assertFalse(manager.deleteMcpServer("idea_mcp"));
    }

    // ==================== SEC-01 闸门 ====================

    @Test(expected = McpInstallRejectedException.class)
    public void upsertShellRunnerRejectedWithoutWrite() throws Exception {
        seedConfigWithExistingSections();

        manager.upsertMcpServer(frontendServer("evil", "sh", args("-c", "rm -rf /")));
    }

    @Test
    public void shellRunnerRejectionLeavesFileUnchanged() throws Exception {
        seedConfigWithExistingSections();
        try {
            manager.upsertMcpServer(frontendServer("evil", "sh", args("-c", "rm -rf /")));
        } catch (McpInstallRejectedException expected) {
            // 闸门拒绝后文件原样:既有条目与其他段未被触碰
            JsonObject root = readConfigFile();
            assertFalse(root.getAsJsonObject("mcp").has("evil"));
            assertEquals("existing", root.getAsJsonObject("mcp").getAsJsonObject("idea_mcp").get("tag").getAsString());
        }
    }

    // ==================== 解析失败拒写 ====================

    @Test(expected = IOException.class)
    public void upsertOnUnparseableFileRefusesWrite() throws Exception {
        Path dir = tempHome.resolve(".config/opencode");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("opencode.json"), "{ not valid json !!!", StandardCharsets.UTF_8);

        manager.upsertMcpServer(frontendServer("any", "npx", null));

        // 到这里说明没抛异常
        throw new AssertionError("Expected IOException for unparseable opencode.json");
    }

    @Test
    public void unparseableFileContentPreservedAfterRefusedWrite() throws Exception {
        Path dir = tempHome.resolve(".config/opencode");
        Files.createDirectories(dir);
        String broken = "{ not valid json !!!";
        Files.writeString(dir.resolve("opencode.json"), broken, StandardCharsets.UTF_8);

        try {
            manager.upsertMcpServer(frontendServer("any", "npx", null));
        } catch (IOException expected) {
            // 拒写而非清空重建:坏文件内容原样保留
            assertEquals(broken, Files.readString(dir.resolve("opencode.json"), StandardCharsets.UTF_8));
        }
    }

    // ==================== 参数校验 ====================

    @Test(expected = IllegalArgumentException.class)
    public void blankIdRejected() throws Exception {
        manager.upsertMcpServer(frontendServer(" ", "npx", null));
    }

    // ==================== helpers ====================

    /** 预置带 provider/permission/mcp.idea_mcp 三段的 opencode.json。 */
    private void seedConfigWithExistingSections() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("provider_p", "keep");
        JsonObject provider = new JsonObject();
        provider.addProperty("p", "keep");
        root.add("provider", provider);
        JsonObject permission = new JsonObject();
        permission.addProperty("x", "keep");
        root.add("permission", permission);
        JsonObject mcp = new JsonObject();
        JsonObject existing = new JsonObject();
        existing.addProperty("type", "local");
        existing.addProperty("tag", "existing");
        mcp.add("idea_mcp", existing);
        root.add("mcp", mcp);

        Path dir = tempHome.resolve(".config/opencode");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("opencode.json"), gson.toJson(root), StandardCharsets.UTF_8);
    }

    /** 构造前端 McpServer 嵌套形状:{id, server:{type, command:string, args, env}, enabled}。 */
    private JsonObject frontendServer(String id, String command, JsonArray args) {
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "stdio");
        spec.addProperty("command", command);
        if (args != null) {
            spec.add("args", args);
        }
        JsonObject env = new JsonObject();
        env.addProperty("K", "v");
        spec.add("env", env);

        JsonObject server = new JsonObject();
        server.addProperty("id", id);
        server.add("server", spec);
        server.addProperty("enabled", true);
        return server;
    }

    private static JsonArray args(String... items) {
        JsonArray array = new JsonArray();
        for (String item : items) {
            array.add(item);
        }
        return array;
    }

    private JsonObject readConfigFile() throws Exception {
        Path configPath = tempHome.resolve(".config/opencode/opencode.json");
        assertTrue(Files.exists(configPath));
        return JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void useTemporaryHome(Path home) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(home.toString());
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // 测试清理尽力而为
                        }
                    });
        }
    }
}
