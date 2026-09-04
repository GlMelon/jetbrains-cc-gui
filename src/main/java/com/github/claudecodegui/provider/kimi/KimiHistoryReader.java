package com.github.claudecodegui.provider.kimi;

import com.github.claudecodegui.cli.common.CliPromptContexts;
import com.github.claudecodegui.handler.history.NativeCliHistoryMessages;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Kimi Code CLI 会话历史读取器(容错实现)。
 * <p>
 * 存储布局(官方 sessions 文档):
 * <pre>
 *   $KIMI_CODE_HOME/sessions/&lt;workDirKey&gt;/&lt;sessionId&gt;/
 *       state.json              → 会话元数据(title/创建时间/cwd 等字段名未全公开,容错读取)
 *       agents/main/wire.jsonl  → 主 agent 事件流(回放/恢复用)
 *   $KIMI_CODE_HOME/session_index.jsonl → 全局索引(v1 不依赖)
 * </pre>
 * ⚠️ state.json 的 cwd 字段名未在官方文档展开,项目过滤采用候选键探测
 * (workDir/workdir/cwd/projectDir/projectPath);全部缺失的会话无法归位到项目 → 跳过不展示。
 * wire.jsonl 行形态按 role 兼容(user/assistant/tool)解析,未知行跳过。
 */
public class KimiHistoryReader {

    private static final Logger LOG = Logger.getInstance(KimiHistoryReader.class);
    private static final String[] CWD_KEYS = {"workDir", "workdir", "cwd", "projectDir", "projectPath"};
    private static final String[] TITLE_KEYS = {"title", "name", "summary"};

    /** kimi CLI 自读的 MCP 配置文件名($KIMI_CODE_HOME 下),CLI 侧约定,单点定义。 */
    public static final String MCP_CONFIG_FILE_NAME = "mcp.json";

    /** 测试/非默认根注入(null=默认 KIMI_CODE_HOME 或 ~/.kimi-code)。 */
    private final Path baseOverride;

    public KimiHistoryReader() {
        this(null);
    }

    public KimiHistoryReader(Path baseOverride) {
        this.baseOverride = baseOverride;
    }

    private Path sessionsRoot() {
        return (baseOverride != null ? baseOverride : kimiHome()).resolve("sessions");
    }

    public List<KimiSessionInfo> listSessions(String projectPath) {
        Path sessionsRoot = sessionsRoot();
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        String normalizedProject = normalizePath(projectPath);
        List<KimiSessionInfo> out = new ArrayList<>();
        try (var workDirs = Files.list(sessionsRoot)) {
            workDirs.filter(Files::isDirectory).forEach(workDir -> {
                try (var idDirs = Files.list(workDir)) {
                    idDirs.filter(Files::isDirectory).forEach(idDir ->
                            readSession(idDir, normalizedProject, out));
                } catch (IOException e) {
                    LOG.debug("[KimiHistory] list " + workDir + " failed: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("[KimiHistory] listSessions failed: " + e.getMessage());
        }
        out.sort((a, b) -> Long.compare(b.lastTimestamp, a.lastTimestamp));
        return out;
    }

    public Path findSessionDir(String sessionId, String projectPath) {
        Path sessionsRoot = sessionsRoot();
        if (!Files.isDirectory(sessionsRoot)) {
            return null;
        }
        try (var dirs = Files.list(sessionsRoot)) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(sessionId))
                    .filter(dir -> Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("state.json")))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean deleteSession(String sessionId, String projectPath) {
        Path dir = findSessionDir(sessionId, projectPath);
        if (dir == null) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.warn("[KimiHistory] delete failed: " + p + " - " + e.getMessage());
                        }
                    });
            return !Files.exists(dir);
        } catch (IOException e) {
            return false;
        }
    }

    /** wire.jsonl → 前端消息列表(main agent)。 */
    public List<JsonObject> loadMessages(Path sessionDir) {
        Path wire = sessionDir.resolve("agents").resolve("main").resolve("wire.jsonl");
        if (!Files.isRegularFile(wire)) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        // ACP wire 事件流的 turn 内聚合缓冲(text/think 分片与工具块按 turn 拼装)
        AcpTurnBuffer turn = new AcpTurnBuffer();
        try {
            for (String line : Files.readAllLines(wire, StandardCharsets.UTF_8)) {
                JsonObject obj = NativeCliHistoryMessages.parseObject(line);
                if (obj == null) {
                    continue;
                }
                if (obj.has("role")) {
                    // 旧 role-based 行(交互/legacy 布局)
                    JsonObject message = toFrontendMessage(obj);
                    if (message != null) {
                        out.add(message);
                    }
                    continue;
                }
                // ACP wire 事件流(v2 引擎,2026-08 实测:type 驱动,无顶层 role)
                consumeAcpEvent(obj, out, turn);
            }
        } catch (IOException e) {
            LOG.warn("[KimiHistory] read " + wire + " failed: " + e.getMessage());
        }
        flushAcpTurn(out, turn);
        return out;
    }

    /** ACP turn 内聚合缓冲:一个 turn 产出一条 assistant(think/text/tool_use)+ 若干 tool_result。 */
    private static final class AcpTurnBuffer {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final List<JsonObject> toolUses = new ArrayList<>();
        final List<JsonObject> toolResults = new ArrayList<>();

        void reset() {
            text.setLength(0);
            thinking.setLength(0);
            toolUses.clear();
            toolResults.clear();
        }
    }

    /**
     * ACP wire 事件行消费(无顶层 role 的行)。识别的变体:
     * <ul>
     *   <li>{@code turn.prompt} → 用户消息(input[].text 拼接,去注入标记);</li>
     *   <li>{@code context.append_loop_event}(event.type=content.part)→
     *       part.type=text/think 累积进当前 turn 缓冲;</li>
     *   <li>{@code context.append_loop_event}(event.type=tool.call / tool.result)→
     *       工具调用块与结果(2026-08 实测形态:toolCallId/name/args;result.output);</li>
     *   <li>{@code turn.ended} → flush 当前 turn 的 assistant 消息 + tool_result 消息。</li>
     * </ul>
     * 其余(metadata、runtime、profile.bind、step.end、usage 等控制事件)消费但不产出。
     */
    private static void consumeAcpEvent(JsonObject line, List<JsonObject> out, AcpTurnBuffer turn) {
        String type = NativeCliHistoryMessages.primitiveString(line, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "turn.prompt" -> {
                flushAcpTurn(out, turn);
                JsonObject user = NativeCliHistoryMessages.userText(
                        cleanInjectedContext(acpPromptText(line)));
                if (user != null) {
                    out.add(user);
                }
            }
            case "context.append_loop_event" -> {
                JsonObject event = line.has("event") && line.get("event").isJsonObject()
                        ? line.getAsJsonObject("event") : null;
                if (event == null) {
                    return;
                }
                String eventType = NativeCliHistoryMessages.primitiveString(event, "type");
                if ("content.part".equals(eventType)) {
                    consumeContentPart(event, turn);
                } else if ("tool.call".equals(eventType)) {
                    consumeToolCall(event, turn);
                } else if ("tool.result".equals(eventType)) {
                    consumeToolResult(event, turn);
                }
            }
            case "turn.ended" -> flushAcpTurn(out, turn);
            default -> {
                // 其它 ACP 控制事件:忽略
            }
        }
    }

    /** content.part 分片:text/think 累积进当前 turn 缓冲;未知 part 类型容错忽略。 */
    private static void consumeContentPart(JsonObject event, AcpTurnBuffer turn) {
        JsonObject part = event.has("part") && event.get("part").isJsonObject()
                ? event.getAsJsonObject("part") : null;
        if (part == null) {
            return;
        }
        String partType = NativeCliHistoryMessages.primitiveString(part, "type");
        if ("text".equals(partType)) {
            String text = NativeCliHistoryMessages.primitiveString(part, "text");
            if (text != null) {
                turn.text.append(text);
            }
        } else if ("think".equals(partType)) {
            String think = NativeCliHistoryMessages.primitiveString(part, "think");
            if (think != null) {
                turn.thinking.append(think);
            }
        }
    }

    /** tool.call 事件 → assistant 的 tool_use 块(toolCallId/name/args)。 */
    private static void consumeToolCall(JsonObject event, AcpTurnBuffer turn) {
        String callId = NativeCliHistoryMessages.primitiveString(event, "toolCallId");
        String name = NativeCliHistoryMessages.primitiveString(event, "name");
        JsonObject args = event.has("args") && event.get("args").isJsonObject()
                ? event.getAsJsonObject("args") : new JsonObject();
        turn.toolUses.add(NativeCliHistoryMessages.toolUseBlock(callId, name, args));
    }

    /** tool.result 事件 → 独立 user(tool_result)消息,随 assistant 之后补发。 */
    private static void consumeToolResult(JsonObject event, AcpTurnBuffer turn) {
        String callId = NativeCliHistoryMessages.primitiveString(event, "toolCallId");
        JsonObject result = event.has("result") && event.get("result").isJsonObject()
                ? event.getAsJsonObject("result") : null;
        String output = result == null ? null
                : NativeCliHistoryMessages.primitiveString(result, "output");
        // output 缺失时退回 result 整体 JSON(不丢内容)
        String content = output != null ? output
                : (result == null ? "" : GsonHolder.GSON.toJson(result));
        boolean isError = result != null && result.has("is_error")
                && result.get("is_error").isJsonPrimitive() && result.get("is_error").getAsBoolean();
        JsonObject message = NativeCliHistoryMessages.toolResultMessage(callId, content, isError);
        if (message != null) {
            turn.toolResults.add(message);
        }
    }

    /** turn.prompt 的 input[].text 拼接。 */
    private static String acpPromptText(JsonObject line) {
        JsonElement input = line.get("input");
        if (input == null || !input.isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : input.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            String text = NativeCliHistoryMessages.primitiveString(el.getAsJsonObject(), "text");
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** 将当前 turn 缓冲 flush 为一条 assistant 消息(thinking/text/tool_use 块)+ tool_result 消息。 */
    private static void flushAcpTurn(List<JsonObject> out, AcpTurnBuffer turn) {
        String text = turn.text.toString();
        String thinking = turn.thinking.toString();
        List<JsonObject> toolUses = List.copyOf(turn.toolUses);
        List<JsonObject> toolResults = List.copyOf(turn.toolResults);
        turn.reset();
        if (!text.isEmpty() || !thinking.isEmpty() || !toolUses.isEmpty()) {
            List<JsonObject> blocks = new ArrayList<>();
            if (!thinking.isEmpty()) {
                blocks.add(NativeCliHistoryMessages.thinkingBlock(thinking));
            }
            if (!text.isEmpty()) {
                blocks.add(NativeCliHistoryMessages.textBlock(text));
            }
            blocks.addAll(toolUses);
            JsonObject message = NativeCliHistoryMessages.assistant(text, blocks);
            if (message != null) {
                out.add(message);
            }
        }
        out.addAll(toolResults);
    }

    /**
     * 剥离插件注入的上下文标记(Opened Files / Referenced Files / Agent Role):
     * kimi 把 lastPrompt 首行当会话 title、turn.prompt 原文入历史,注入段会污染展示。
     * 委托 {@link CliPromptContexts#stripInjectedContext}(与实时 SESSION_TITLE 链同源)。
     */
    static String cleanInjectedContext(String text) {
        return CliPromptContexts.stripInjectedContext(text);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────────

    private void readSession(Path idDir, String normalizedProject, List<KimiSessionInfo> out) {
        Path stateFile = idDir.resolve("state.json");
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        JsonObject state;
        try {
            state = GsonHolder.GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8),
                    JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[KimiHistory] parse state.json failed (" + idDir + "): " + e.getMessage());
            return;
        }
        if (state == null) {
            return;
        }
        String sessionCwd = detectCwd(state);
        if (normalizedProject != null && !normalizedProject.isEmpty()) {
            // 无法确定归属或非本项目 → 跳过(容错立场:不确定的不猜)
            if (sessionCwd == null) {
                return;
            }
            String n = normalizePath(sessionCwd);
            if (!n.equals(normalizedProject) && !n.startsWith(normalizedProject + "/")) {
                return;
            }
        }
        KimiSessionInfo info = new KimiSessionInfo();
        info.sessionId = idDir.getFileName().toString();
        // title=lastPrompt 首行(kimi 侧行为),注入段(Opened Files 等)会污染列表展示,剥除
        info.title = NativeCliHistoryMessages.truncateTitle(
                cleanInjectedContext(firstString(state, TITLE_KEYS)));
        info.cwd = sessionCwd == null ? "" : sessionCwd;
        info.lastTimestamp = lastModifiedOf(stateFile);
        info.firstTimestamp = info.lastTimestamp;
        try {
            info.fileSize = Files.size(stateFile);
        } catch (IOException ignored) {
            // 非关键统计
        }
        info.messageCount = approximateMessageCount(idDir.resolve("agents").resolve("main").resolve("wire.jsonl"));
        out.add(info);
    }

    /** 统计上限:超过此大小的 wire 不做计数(列表是轻量场景,大文件计数价值低)。 */
    private static final long MESSAGE_COUNT_MAX_WIRE_BYTES = 2L * 1024 * 1024;

    /**
     * 会话消息数近似统计(非关键统计,容错):turn.prompt≈user、turn.ended≈assistant、
     * tool.result / legacy role 行各一条。子串匹配避免全量 JSON 解析。
     */
    static int approximateMessageCount(Path wire) {
        try {
            if (!Files.isRegularFile(wire) || Files.size(wire) > MESSAGE_COUNT_MAX_WIRE_BYTES) {
                return 0;
            }
            int count = 0;
            for (String line : Files.readAllLines(wire, StandardCharsets.UTF_8)) {
                if (line.contains("\"turn.prompt\"") || line.contains("\"turn.ended\"")
                        || line.contains("\"tool.result\"") || line.contains("\"role\"")) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 候选键探测 cwd。 */
    static String detectCwd(JsonObject state) {
        String v = firstString(state, CWD_KEYS);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        // 嵌套对象兜底:{meta:{...}} / {session:{...}}
        for (String wrapper : new String[]{"meta", "session"}) {
            if (state.has(wrapper) && state.get(wrapper).isJsonObject()) {
                v = firstString(state.getAsJsonObject(wrapper), CWD_KEYS);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return null;
    }

    private static String firstString(JsonObject obj, String[] keys) {
        for (String key : keys) {
            String v = NativeCliHistoryMessages.primitiveString(obj, key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static long lastModifiedOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 单条 wire 行 → 前端消息(role:user/assistant 文本与 tool_calls、role:tool 结果);未知行 null。 */
    static JsonObject toFrontendMessage(JsonObject line) {
        String role = NativeCliHistoryMessages.primitiveString(line, "role");
        if (role == null) {
            return null;
        }
        switch (role) {
            case "user": {
                return NativeCliHistoryMessages.userText(extractText(line));
            }
            case "assistant": {
                String text = extractText(line);
                List<JsonObject> blocks = new ArrayList<>();
                JsonElement calls = line.get("tool_calls");
                if (calls != null && calls.isJsonArray()) {
                    for (JsonElement el : calls.getAsJsonArray()) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject call = el.getAsJsonObject();
                        String name = firstNonBlank(
                                NativeCliHistoryMessages.primitiveString(call, "name"),
                                NativeCliHistoryMessages.functionNameOf(call));
                        String id = NativeCliHistoryMessages.primitiveString(call, "id");
                        blocks.add(NativeCliHistoryMessages.toolUseBlock(
                                id, name, NativeCliHistoryMessages.argumentsObjectOf(call)));
                    }
                }
                return NativeCliHistoryMessages.assistant(text, blocks);
            }
            case "tool": {
                return NativeCliHistoryMessages.toolResultMessage(
                        NativeCliHistoryMessages.primitiveString(line, "tool_call_id"),
                        contentAsString(line), false);
            }
            default:
                return null;
        }
    }

    private static String extractText(JsonObject line) {
        JsonElement content = line.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            return content.getAsString();
        }
        StringBuilder sb = new StringBuilder();
        collectText(content, sb);
        return sb.toString();
    }

    private static void collectText(JsonElement el, StringBuilder sb) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            sb.append(el.getAsString());
        } else if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String text = NativeCliHistoryMessages.primitiveString(obj, "text");
            if (text != null) {
                sb.append(text);
            }
        } else if (el.isJsonArray()) {
            el.getAsJsonArray().forEach(item -> collectText(item, sb));
        }
    }

    private static String contentAsString(JsonObject line) {
        JsonElement content = line.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        return GsonHolder.GSON.toJson(content);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    public static Path kimiHome() {
        String env = System.getenv("KIMI_CODE_HOME");
        String base = env != null && !env.isBlank()
                ? env.trim()
                : com.github.claudecodegui.util.PlatformUtils.getHomeDirectory();
        return env != null && !env.isBlank()
                ? Path.of(base)
                : Path.of(base, ".kimi-code");
    }

    /** 会话列表条目(公有字段,序列化即 wire 形态)。 */
    public static class KimiSessionInfo {
        public String sessionId;
        public String title = "";
        public int messageCount;
        public long lastTimestamp;
        public long firstTimestamp;
        public String cwd = "";
        public long fileSize;
    }
}
