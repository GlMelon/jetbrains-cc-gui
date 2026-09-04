package com.github.claudecodegui.provider.kimi;

import com.github.claudecodegui.handler.history.NativeCliHistoryMessages;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kimi 会话实际加载 MCP 服务的发现读取器。
 * <p>
 * kimi 会话的 MCP 注入有两条路径:① 插件经 ACP session/new 注入 melon_gateway
 * ({@code KimiAcpCliSession.buildMcpServers});② kimi CLI 自读 {@code ~/.kimi-code/mcp.json}。
 * 不论注入方式,CLI 发现工具后都会在会话落盘
 * {@code agents/main/wire.jsonl} 写一条 {@code mcp.tools_discovered} 事件
 * (每 server 一条):{@code {"type":"mcp.tools_discovered","agentId":"main","serverName":...,
 * "hash":...,"tools":[{name,description,inputSchema}, ...]}}——这是「实际加载」的权威证据。
 */
public class KimiMcpDiscoveryReader {

    private static final Logger LOG = Logger.getInstance(KimiMcpDiscoveryReader.class);

    /** wire 解析上限:超过此大小跳过(面板是轻量场景,超大文件解析价值低)。 */
    private static final long MAX_WIRE_BYTES = 64L * 1024 * 1024;

    /** 行预过滤子串:先子串命中再 JSON 解析,避免大文件全量解析(对齐 approximateMessageCount 范式)。 */
    private static final String EVENT_MARKER = "mcp.tools_discovered";

    private final KimiHistoryReader historyReader;

    public KimiMcpDiscoveryReader() {
        this(new KimiHistoryReader());
    }

    /** 测试/非默认根注入(经 {@link KimiHistoryReader#KimiHistoryReader(Path)})。 */
    public KimiMcpDiscoveryReader(KimiHistoryReader historyReader) {
        this.historyReader = historyReader;
    }

    /**
     * 读取该会话实际加载的 MCP server 列表(按 serverName 去重保序,
     * toolCount 取该 server 最后一条 tools_discovered 事件的 tools 长度)。
     *
     * @return {@code null} = 会话目录不存在 / wire 不可读(面板按不可用降级);
     *         空列表 = 会话在但无 MCP 发现事件。
     */
    public List<DiscoveredMcpServer> readDiscoveredServers(String sessionId, String projectPath) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path sessionDir = historyReader.findSessionDir(sessionId, projectPath);
        if (sessionDir == null) {
            return null;
        }
        return parseWire(sessionDir.resolve("agents").resolve("main").resolve("wire.jsonl"));
    }

    /**
     * 解析核心(包私有,fixture 直测)。{@code null} = wire 不存在 / 读失败。
     */
    static List<DiscoveredMcpServer> parseWire(Path wire) {
        try {
            if (!Files.isRegularFile(wire)) {
                return null;
            }
            if (Files.size(wire) > MAX_WIRE_BYTES) {
                LOG.warn("[KimiMcpDiscovery] wire too large, skipped: " + wire);
                return List.of();
            }
            // LinkedHashMap:按首次发现保序;重复事件 put 覆盖 → toolCount 取最后一条。
            Map<String, Integer> toolCountByServer = new LinkedHashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(wire, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains(EVENT_MARKER)) {
                        continue;
                    }
                    parseEvent(line, toolCountByServer);
                }
            }
            List<DiscoveredMcpServer> out = new ArrayList<>();
            toolCountByServer.forEach((name, toolCount) -> out.add(new DiscoveredMcpServer(name, toolCount)));
            return out;
        } catch (IOException e) {
            LOG.debug("[KimiMcpDiscovery] read failed: " + wire + " - " + e.getMessage());
            return null;
        }
    }

    /** 单行事件解析:畸形行 / 缺 serverName 跳过不致死。 */
    private static void parseEvent(String line, Map<String, Integer> toolCountByServer) {
        try {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            String serverName = NativeCliHistoryMessages.primitiveString(event, "serverName");
            if (serverName == null || serverName.isBlank()) {
                return;
            }
            int toolCount = 0;
            JsonElement tools = event.get("tools");
            if (tools != null && tools.isJsonArray()) {
                toolCount = tools.getAsJsonArray().size();
            }
            toolCountByServer.put(serverName, toolCount);
        } catch (JsonParseException | IllegalStateException e) {
            // 畸形行跳过(容错,对齐 KimiHistoryReader 未知行跳过策略)
        }
    }

    /** 发现的 MCP server(名称 + 工具数)。 */
    public record DiscoveredMcpServer(String name, int toolCount) {
    }
}
