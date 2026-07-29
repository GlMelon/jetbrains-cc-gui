package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * McpSettingsService 领域委托测试(A3 领域拆分第五步,docs §A3)。
 *
 * <p>McpSettingsService 是「持有并构造 {@link McpServerManager} 的领域入口」(薄包装,零自身逻辑),
 * 本测试聚焦<b>委托链语义不漂移</b> + <b>CSS Facade 转发</b>,不重复 McpServerManager 内部矩阵
 * (~/.claude.json 主路径合并 / project-level / disabledMcpServers 过滤由 McpServerManager 自身
 * 未来补测试守门)。
 *
 * <p><b>夹具隔离</b>:反射注入 {@code PlatformUtils.cachedRealHomeDir} 指向隔离临时 home(参照
 * {@link ModelRegistrySettingsServiceTest})。该字段是 home SSOT —— {@link PlatformUtils#getHomeDirectory()}
 * 返回它,{@code NodeDetector.resolveHomeForFileOps()} → {@code WslPathUtil.resolveHomeForFileOps()}
 * (非 WSL 分支)→ {@link PlatformUtils#getHomeDirectory()},故 MCP 的 {@code ~/.claude.json}
 * <b>与</b> fallback 的 {@code ~/.codemoss/config.json} 双路径均落在隔离临时 home,绝不碰真实环境。
 *
 * <p><b>路径选择</b>:临时 home <b>无 ~/.claude.json</b>,强制所有 MCP 操作走 fallback
 * {@code ~/.codemoss/config.json#mcpServers} 路径(经 CSS readConfig/writeConfig → ConfigRepository
 * 原子写 + CAS),端到端验证 Service → McpServerManager → config.json 委托链。
 * {@code getMcpServersReturnsEmptyWhenNoConfig} 同时充当隔离 canary —— 若隔离失效读到真实
 * ~/.claude.json 的 servers,该用例会 fail 而非静默污染。
 */
public class McpSettingsServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== 委托链:Service → McpServerManager(fallback config.json 路径)====================

    @Test
    public void getMcpServersReturnsEmptyWhenNoConfig() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-empty-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        // 隔离 canary:临时 home 无 ~/.claude.json 无 config.json mcpServers 段 → 空。
        // 若非空说明反射注入未隔离 home,读到了真实 ~/.claude.json(需立即中止排查)。
        assertTrue("隔离失效或残留数据:临时 home 下 getMcpServers 应为空", svc.getMcpServers().isEmpty());
    }

    @Test
    public void upsertThenGetRoundTripsViaFallbackConfigJson() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-upsert-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        JsonObject server = new JsonObject();
        server.addProperty("id", "test-server");
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "stdio");
        spec.addProperty("command", "npx");
        server.add("server", spec);

        svc.upsertMcpServer(server);

        List<JsonObject> servers = svc.getMcpServers();
        assertFalse("upsert 后 getMcpServers 应非空(fallback config.json 往返)", servers.isEmpty());
        assertTrue("upsert 的 server 应在 getMcpServers 中可见",
                servers.stream().anyMatch(s -> "test-server".equals(s.get("id").getAsString())));
    }

    @Test
    public void upsertWithoutIdThrowsIllegalArgument() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-noid-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        try {
            svc.upsertMcpServer(new JsonObject()); // 无 id
            fail("无 id 的 server 应抛 IllegalArgumentException(McpServerManager.upsertMcpServer 契约透传)");
        } catch (IllegalArgumentException expected) {
            // ok —— 委托链保留 McpServerManager 的参数校验
        }
    }

    @Test
    public void deleteRemovesServerViaFallbackConfigJson() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-delete-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        JsonObject server = new JsonObject();
        server.addProperty("id", "to-delete");
        svc.upsertMcpServer(server);
        assertFalse(svc.getMcpServers().isEmpty());

        assertTrue("delete 已存在的 server 应返回 true", svc.deleteMcpServer("to-delete"));
        assertTrue("delete 后 server 不应在 getMcpServers 中",
                svc.getMcpServers().stream().noneMatch(s -> "to-delete".equals(s.get("id").getAsString())));
    }

    @Test
    public void deleteReturnsFalseForUnknownServer() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-delete-unknown-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        assertFalse("delete 不存在的 server 应返回 false", svc.deleteMcpServer("no-such-server"));
    }

    @Test
    public void validateMcpServerAcceptsValidStdioServer() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-validate-ok-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        JsonObject server = new JsonObject();
        server.addProperty("name", "valid-server");
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "stdio");
        spec.addProperty("command", "npx");
        server.add("server", spec);

        Map<String, Object> result = svc.validateMcpServer(server);
        assertNotNull(result);
        assertEquals("合法 stdio server(name + command)应校验通过", Boolean.TRUE, result.get("valid"));
    }

    @Test
    public void validateMcpServerRejectsMissingName() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-validate-bad-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        JsonObject server = new JsonObject(); // 无 name
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "stdio");
        spec.addProperty("command", "npx");
        server.add("server", spec);

        Map<String, Object> result = svc.validateMcpServer(server);
        assertEquals("缺 name 应校验失败", Boolean.FALSE, result.get("valid"));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertTrue("缺 name 应报 'Server name must not be empty'",
                errors.stream().anyMatch(e -> e.toLowerCase().contains("name")));
    }

    @Test
    public void getMcpServersWithProjectPathDelegatesWithoutThrowing() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-projpath-home"));
        McpSettingsService svc = newMcpSettingsService(new CodemossSettingsService());

        // fallback 路径下 projectPath 参数透传(无 ~/.claude.json 的 projects 段,结果同 global = 空)。
        List<JsonObject> withPath = svc.getMcpServersWithProjectPath("/some/project");
        assertNotNull(withPath);
        assertTrue(withPath.isEmpty());
    }

    // ==================== 委托链(CSS Facade 转发 → Service)====================

    @Test
    public void delegationViaCssFacade() throws Exception {
        useTemporaryHome(Files.createTempDirectory("mcp-svc-css-home"));
        CodemossSettingsService css = new CodemossSettingsService();

        assertTrue(css.getMcpServers().isEmpty());

        JsonObject server = new JsonObject();
        server.addProperty("id", "css-facade-server");
        css.upsertMcpServer(server);

        assertTrue("经 CSS Facade 转发 upsert/getMcpServers,行为应与直调 Service 一致",
                css.getMcpServers().stream()
                        .anyMatch(s -> "css-facade-server".equals(s.get("id").getAsString())));

        // validate 经 CSS 转发仍返回 Map(valid/errors)。
        Map<String, Object> validated = css.validateMcpServer(server);
        assertNotNull(validated);
    }

    // ==================== helpers ====================

    private McpSettingsService newMcpSettingsService(CodemossSettingsService css) {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        ClaudeSettingsManager claudeSettingsManager = new ClaudeSettingsManager(gson, new ConfigPathManager());
        return new McpSettingsService(SettingsTestConfig.create().configStore(), gson, claudeSettingsManager);
    }

    private void useTemporaryHome(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
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
}
