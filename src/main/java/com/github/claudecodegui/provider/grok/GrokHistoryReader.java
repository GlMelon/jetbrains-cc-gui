package com.github.claudecodegui.provider.grok;

import com.github.claudecodegui.handler.history.NativeCliHistoryMessages;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Grok 会话历史读取器。
 * <p>
 * 存储布局(headless 会话):{@code ~/.grok/sessions/<jsEncodeURIComponent(cwd)>/<sessionId>/chat_history.jsonl}。
 * 会话父目录名是整条 cwd 的 JS encodeURIComponent(与 {@link GrokToolHistoryTailer#jsEncodeURIComponent}
 * 同一编码约定),故「项目下会话」的过滤需对每个目录名做<b>解码后前缀匹配</b>(精确目录只能命中本目录)。
 * <p>
 * 消息行(chat_history.jsonl):
 * <ul>
 *   <li>{@code type:"assistant"}(+可选 tool_calls) / {@code type:"tool_result"} /
 *       用户消息形态以实测为准——解析器对未知形态容错跳过。</li>
 * </ul>
 * 纯文件扫描,零 Node 依赖(对称 CodexHistoryReader)。字段缺失时优雅降级为空列表。
 */
public class GrokHistoryReader {

    private static final Logger LOG = Logger.getInstance(GrokHistoryReader.class);
    private static final int TITLE_PREVIEW_CHARS = 60;

    /** 测试/非默认根注入(GROK_HOME 形态的 home 基目录,null=默认)。 */
    private final Path baseOverride;

    public GrokHistoryReader() {
        this(null);
    }

    public GrokHistoryReader(Path grokHomeBase) {
        this.baseOverride = grokHomeBase;
    }

    private Path sessionsRoot() {
        return (baseOverride != null ? baseOverride : defaultBase()).resolve("sessions");
    }

    private static Path defaultBase() {
        String env = System.getenv("GROK_HOME");
        return Path.of(env != null && !env.isBlank()
                ? env.trim()
                : com.github.claudecodegui.util.PlatformUtils.getHomeDirectory(), ".grok");
    }

    public List<GrokSessionInfo> listSessions(String projectPath) {
        Path sessionsRoot = sessionsRoot();
        if (sessionsRoot == null || !Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        String normalizedProject = normalizePath(projectPath);
        List<GrokSessionInfo> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> matchesProject(dir.getFileName().toString(), normalizedProject))
                    .forEach(dir -> {
                        try (Stream<Path> ids = Files.list(dir)) {
                            ids.filter(Files::isDirectory).forEach(idDir ->
                                    out.add(readSessionInfo(idDir)));
                        } catch (IOException e) {
                            LOG.debug("[GrokHistory] list id dirs failed: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warn("[GrokHistory] list sessions failed: " + e.getMessage());
        }
        out.sort((a, b) -> Long.compare(b.lastTimestamp, a.lastTimestamp));
        return out;
    }

    /** 会话按文件聚合为 Claude 兼容前端消息(块挂 raw.content,形状契约见 {@link NativeCliHistoryMessages})。 */
    public List<JsonObject> loadMessages(Path sessionDir) {
        Path history = sessionDir.resolve("chat_history.jsonl");
        if (!Files.isRegularFile(history)) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
                JsonObject obj = parseObject(line);
                if (obj == null) {
                    continue;
                }
                JsonObject message = toFrontendMessage(obj);
                if (message != null) {
                    out.add(message);
                }
            }
        } catch (IOException e) {
            LOG.warn("[GrokHistory] read " + history + " failed: " + e.getMessage());
        }
        return out;
    }

    /** 定位会话目录(projectPath 仅用于缩小解码匹配;找不到返回 null)。 */
    public Path findSessionDir(String sessionId, String projectPath) {
        Path sessionsRoot = sessionsRoot();
        if (sessionsRoot == null || !Files.isDirectory(sessionsRoot)) {
            return null;
        }
        String normalizedProject = normalizePath(projectPath);
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            Path hit = dirs.filter(Files::isDirectory)
                    .filter(dir -> matchesProject(dir.getFileName().toString(), normalizedProject))
                    .map(dir -> dir.resolve(sessionId))
                    .filter(Files::isDirectory)
                    .findFirst().orElse(null);
            return hit;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean deleteSession(String sessionId, String projectPath) {
        Path dir = findSessionDir(sessionId, projectPath);
        if (dir == null) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.warn("[GrokHistory] delete failed: " + p + " - " + e.getMessage());
                        }
                    });
            return !Files.exists(dir);
        } catch (IOException e) {
            LOG.warn("[GrokHistory] delete walk failed: " + e.getMessage());
            return false;
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────────────

    /**
     * 目录名(JS percent-encoding 的整条 cwd)解码后与项目路径做「相等或子路径」匹配。
     * 解码失败的目录(非 cwd 编码形态)跳过。
     */
    static boolean matchesProject(String encodedDirName, String normalizedProject) {
        if (normalizedProject.isEmpty()) {
            return true;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(encodedDirName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String normalizedCwd = normalizePath(decoded);
        return normalizedCwd.equals(normalizedProject)
                || normalizedCwd.startsWith(normalizedProject + "/");
    }

    private GrokSessionInfo readSessionInfo(Path idDir) {
        GrokSessionInfo info = new GrokSessionInfo();
        info.sessionId = idDir.getFileName().toString();
        info.cwd = decodeDirCwd(idDir.getParent().getFileName().toString());
        Path history = idDir.resolve("chat_history.jsonl");
        if (Files.isRegularFile(history)) {
            try {
                long size = Files.size(history);
                info.fileSize = size;
                info.lastTimestamp = Files.getLastModifiedTime(history).toMillis();
                info.firstTimestamp = info.lastTimestamp;
                var meta = summarize(history);
                info.messageCount = meta.messageCount();
                info.title = meta.titlePreview();
            } catch (IOException e) {
                LOG.debug("[GrokHistory] stat " + history + " failed: " + e.getMessage());
            }
        }
        return info;
    }

    private record HistoryMeta(int messageCount, String titlePreview) {
    }

    private HistoryMeta summarize(Path history) throws IOException {
        int count = 0;
        String titlePreview = "";
        for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
            JsonObject obj = parseObject(line);
            if (obj == null) {
                continue;
            }
            String type = primitiveString(obj, "type");
            if (!"assistant".equals(type) && !"tool_result".equals(type) && !"user".equals(type)) {
                continue;
            }
            count++;
            if (titlePreview.isEmpty()) {
                String preview = previewTextOf(obj);
                if (!preview.isBlank()) {
                    titlePreview = truncate(preview);
                }
            }
        }
        return new HistoryMeta(count, titlePreview);
    }

    private String previewTextOf(JsonObject obj) {
        try {
            JsonElement data = obj.has("data") ? obj.get("data") : null;
            if (data != null && data.isJsonPrimitive()) {
                return data.getAsString();
            }
            JsonObject message = obj.has("message") && obj.get("message").isJsonObject()
                    ? obj.getAsJsonObject("message") : null;
            JsonElement content = message != null ? message.get("content") : obj.get("content");
            if (content != null && content.isJsonPrimitive()) {
                return content.getAsString();
            }
            if (content != null && content.isJsonArray() && content.getAsJsonArray().size() > 0) {
                JsonElement first = content.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    String text = primitiveString(first.getAsJsonObject(), "text");
                    if (text == null) {
                        text = primitiveString(first.getAsJsonObject(), "content");
                    }
                    return text == null ? "" : text;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    /**
     * 单条历史行 → 前端 Claude 兼容消息;不认识的形态返回 null。
     * <p>
     * 已知实形态(对称 ai-bridge/services/grok/history-tools.js 解析面):
     * <ul>
     *   <li>{@code {"type":"assistant","tool_calls":[{id,name|function.arguments}]}}(工具发起)</li>
     *   <li>{@code {"type":"tool_result","tool_call_id":..,"content":..}}(工具完成)</li>
     *   <li>文本/用户行(assistant 带 data/content 或 user)——容错覆盖</li>
     * </ul>
     */
    private static JsonObject toFrontendMessage(JsonObject line) {
        String type = primitiveString(line, "type");
        if (type == null) {
            return null;
        }
        switch (type) {
            case "assistant":
                return assistantFromLine(line);
            case "tool_result": {
                String callId = primitiveString(line, "tool_call_id");
                if (callId == null) {
                    return null;
                }
                JsonObject front = baseMessage("user");
                front.addProperty("content", "");
                JsonArray blocks = new JsonArray();
                JsonObject resultBlock = new JsonObject();
                resultBlock.addProperty("type", "tool_result");
                resultBlock.addProperty("tool_use_id", callId);
                resultBlock.addProperty("is_error", false);
                resultBlock.addProperty("content", stringContent(line.get("content")));
                blocks.add(resultBlock);
                front.add("raw", NativeCliHistoryMessages.rawEnvelope("user", blocks));
                return front;
            }
            case "user": {
                String text = firstNonBlank(primitiveString(line, "data"),
                        primitiveString(line, "content"));
                if (text == null || text.isBlank()) {
                    return null;
                }
                JsonObject front = baseMessage("user");
                front.addProperty("content", text);
                return front;
            }
            default:
                return null;
        }
    }

    /** assistant 行:文本(data 字段或信封 content 文本块)+ 可选 tool_calls 工具块。 */
    private static JsonObject assistantFromLine(JsonObject line) {
        String text = firstNonBlank(primitiveString(line, "data"));
        JsonArray blocks = new JsonArray();
        try {
            if (line.has("message") && line.get("message").isJsonObject()) {
                JsonElement contentEl = line.getAsJsonObject("message").get("content");
                if (contentEl != null && contentEl.isJsonArray()) {
                    for (JsonElement el : contentEl.getAsJsonArray()) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        addIfTextOrTool(blocks, el.getAsJsonObject());
                        if (text == null && "text".equals(primitiveString(el.getAsJsonObject(), "type"))) {
                            text = primitiveString(el.getAsJsonObject(), "text");
                        }
                    }
                }
            } else if (line.has("content")) {
                JsonElement contentEl = line.get("content");
                if (contentEl.isJsonPrimitive()) {
                    text = text == null ? contentEl.getAsString() : text;
                } else if (contentEl.isJsonArray()) {
                    for (JsonElement el : contentEl.getAsJsonArray()) {
                        if (el.isJsonObject()) {
                            addIfTextOrTool(blocks, el.getAsJsonObject());
                            if (text == null && "text".equals(primitiveString(el.getAsJsonObject(), "type"))) {
                                text = primitiveString(el.getAsJsonObject(), "text");
                            }
                        }
                    }
                }
            }
            if (line.has("tool_calls") && line.get("tool_calls").isJsonArray()) {
                for (JsonElement el : line.getAsJsonArray("tool_calls")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    addIfTextOrTool(blocks, el.getAsJsonObject());
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        if ((text == null || text.isEmpty()) && blocks.size() == 0) {
            return null;
        }
        JsonObject front = baseMessage("assistant");
        front.addProperty("content", text == null ? "" : text);
        if (blocks.size() > 0) {
            front.add("raw", NativeCliHistoryMessages.rawEnvelope("assistant", blocks));
        }
        return front;
    }

    /** 把 text/tool_use 形态的对象归一为 wire 块(name 可在顶层或 function 包装内)。 */
    private static void addIfTextOrTool(JsonArray blocksOut, JsonObject block) {
        String blockType = primitiveString(block, "type");
        if ("text".equals(blockType)) {
            String text = primitiveString(block, "text");
            if (text != null) {
                JsonObject out = new JsonObject();
                out.addProperty("type", "text");
                out.addProperty("text", text);
                blocksOut.add(out);
            }
            return;
        }
        String toolName = firstNonBlank(primitiveString(block, "name"), functionField(block, "name"));
        if (!"tool_use".equals(blockType) && toolName == null) {
            return;
        }
        String id = primitiveString(block, "id");
        JsonElement argsRaw = block.has("arguments") ? block.get("arguments")
                : functionArgumentRaw(block);
        JsonObject out = new JsonObject();
        out.addProperty("type", "tool_use");
        out.addProperty("id", id == null ? "" : id);
        out.addProperty("name", toolName == null ? "tool" : toolName);
        out.add("input", parseArgumentsObject(argsRaw));
        blocksOut.add(out);
    }

    private static JsonElement functionArgumentRaw(JsonObject block) {
        if (block.has("function") && block.get("function").isJsonObject()) {
            return block.getAsJsonObject("function").get("arguments");
        }
        return null;
    }

    private static JsonObject parseArgumentsObject(JsonElement raw) {
        JsonObject out = new JsonObject();
        if (raw == null || raw.isJsonNull()) {
            return out;
        }
        if (raw.isJsonObject()) {
            return raw.getAsJsonObject();
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(raw.getAsString());
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return out;
    }

    private static String functionField(JsonObject block, String key) {
        if (block.has("function") && block.get("function").isJsonObject()) {
            return primitiveString(block.getAsJsonObject("function"), key);
        }
        return null;
    }

    private static String stringContent(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return GsonHolder.GSON.toJson(el);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static JsonObject baseMessage(String type) {
        JsonObject front = new JsonObject();
        front.addProperty("type", type);
        return front;
    }

    private static String decodeDirCwd(String encodedDirName) {
        try {
            return normalizePath(URLDecoder.decode(encodedDirName, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String truncate(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= TITLE_PREVIEW_CHARS ? trimmed
                : trimmed.substring(0, TITLE_PREVIEW_CHARS) + "…";
    }

    private static String primitiveString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()
                || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static JsonObject parseObject(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return GsonHolder.GSON.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 会话列表条目(公有字段,序列化即 wire 形态)。 */
    public static class GrokSessionInfo {
        public String sessionId;
        public String title = "";
        public int messageCount;
        public long lastTimestamp;
        public long firstTimestamp;
        public String cwd = "";
        public long fileSize;
    }
}
