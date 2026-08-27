package com.github.claudecodegui.provider.kimi;

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
        try {
            for (String line : Files.readAllLines(wire, StandardCharsets.UTF_8)) {
                JsonObject obj = NativeCliHistoryMessages.parseObject(line);
                JsonObject message = obj == null ? null : toFrontendMessage(obj);
                if (message != null) {
                    out.add(message);
                }
            }
        } catch (IOException e) {
            LOG.warn("[KimiHistory] read " + wire + " failed: " + e.getMessage());
        }
        return out;
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
        info.title = NativeCliHistoryMessages.truncateTitle(firstString(state, TITLE_KEYS));
        info.cwd = sessionCwd == null ? "" : sessionCwd;
        info.lastTimestamp = lastModifiedOf(stateFile);
        info.firstTimestamp = info.lastTimestamp;
        try {
            info.fileSize = Files.size(stateFile);
        } catch (IOException ignored) {
            // 非关键统计
        }
        out.add(info);
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
