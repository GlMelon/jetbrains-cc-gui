package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grok CLI 工具事件实时投影(移植自 ai-bridge/services/grok/history-tools.js)。
 * <p>
 * Grok headless streaming-json stdout 不含工具事件;工具调用只出现在
 * {@code ~/.grok/sessions/<encodeURIComponent(cwd)>/<sessionId>/chat_history.jsonl}:
 * <ul>
 *   <li>{@code type:"assistant"} 带 {@code tool_calls[]} → started</li>
 *   <li>{@code type:"tool_result"} 带 {@code tool_call_id/content} → completed</li>
 * </ul>
 * 本类对该文件做字节偏移增量尾随({@link #pollOnce}),把新信号格式化为
 * {@code [MESSAGE] {...}} marker 行供 {@link GrokCliStreamParser} 统一消费。
 * 实例持有单轮可变状态(每次发送新建,对称 MarkerCliStreamParser 生命周期)。
 */
final class GrokToolHistoryTailer {

    private static final Logger LOG = Logger.getInstance(GrokToolHistoryTailer.class);
    /** 单条工具结果字符上限(对称 JS MAX_TOOL_RESULT_CHARS)。 */
    private static final int MAX_TOOL_RESULT_CHARS = 20_000;

    /** resume 场景首见即含全部历史:基线设为当时文件末尾,跳过本轮之前的工具信号。 */
    private final boolean skipExistingOnBaseline;
    private final Path historyPath;

    private boolean baselineSet;
    private long byteOffset;
    private final StringBuilder carry = new StringBuilder();
    private final Set<String> seenStarted = new HashSet<>();
    private final Set<String> seenCompleted = new HashSet<>();
    private int syntheticCounter;

    GrokToolHistoryTailer(Path grokHome, String cwd, String sessionId, boolean resumeSession) {
        this.skipExistingOnBaseline = resumeSession;
        this.historyPath = resolveChatHistoryPath(grokHome, cwd, sessionId);
    }

    Path historyPath() {
        return historyPath;
    }

    /**
     * 解析 chat_history.jsonl 路径:
     * {@code <grokHome>/sessions/<jsEncodedCwd>/<sessionId>/chat_history.jsonl}。
     * cwd/sessionId 为空或 sessionId 含路径分隔符/{@code ..}(防穿越)时返回 null。
     */
    static Path resolveChatHistoryPath(Path grokHome, String cwd, String sessionId) {
        if (grokHome == null || cwd == null || cwd.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String id = sessionId.trim();
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            return null;
        }
        return grokHome.resolve("sessions")
                .resolve(jsEncodeURIComponent(normalizeCwd(cwd)))
                .resolve(id)
                .resolve("chat_history.jsonl");
    }

    /** 归一化 cwd:反斜杠 → 正斜杠、去尾部斜杠(Grok CLI 会话父目录命名前置处理)。 */
    static String normalizeCwd(String cwd) {
        String normalized = cwd.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * JS {@code encodeURIComponent} 的 Java 等价实现(Grok 用它命名会话父目录):
     * URLEncoder 多转义的空格({@code +})与 JS 不转义字符({@code ! ' ( ) * ~})全部还原。
     */
    static String jsEncodeURIComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%2A", "*")
                .replace("%7E", "~");
    }

    /**
     * 从当前偏移读取文件新增字节,返回完整行的工具信号 marker 行列表。
     * 文件尚未出现/无新增/瞬时 IO 错误时返回空列表(文件可能在本轮中途才出现,
     * 对称 JS 静默忽略策略)。字节级按 {@code \n} 切分安全(UTF-8 多字节不含 0x0A),
     * 残行留待下轮拼接(StringDecoder 等价)。
     */
    synchronized List<String> pollOnce() {
        if (historyPath == null || !Files.isRegularFile(historyPath)) {
            return List.of();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(historyPath);
        } catch (IOException e) {
            LOG.debug("[GrokTailer] read chat_history failed (transient): " + e.getMessage());
            return List.of();
        }
        long fileLen = bytes.length;
        if (!baselineSet) {
            baselineSet = true;
            if (skipExistingOnBaseline) {
                // resume:首见文件里已有的内容都先于本轮 → 全部跳过。
                // 文件可能在首次轮询后才出现,故基线不能依赖"曾见过文件缺失"。
                byteOffset = fileLen;
                carry.setLength(0);
                return List.of();
            }
            byteOffset = 0;
            carry.setLength(0);
        }
        if (fileLen < byteOffset) {
            // 文件被截断/重写:从头重读。
            byteOffset = 0;
            carry.setLength(0);
        }
        if (fileLen == byteOffset && carry.length() == 0) {
            return List.of();
        }
        int start = (int) Math.min(byteOffset, fileLen);
        byteOffset = fileLen;
        String chunk = new String(bytes, start, (int) fileLen - start, StandardCharsets.UTF_8);
        String combined = carry.length() > 0 ? carry + chunk : chunk;

        int lastNl = combined.lastIndexOf('\n');
        String complete;
        if (lastNl < 0) {
            carry.append(combined);
            complete = "";
        } else {
            complete = combined.substring(0, lastNl + 1);
            carry.setLength(0);
            carry.append(combined.substring(lastNl + 1));
        }
        return drainChunk(complete);
    }

    /**
     * 解析一个 JSONL 文本块,产出新的工具信号 marker 行(seen 集合幂等去重,
     * 对称 JS drainToolSignalsFromChunk)。包级可见供单测。
     */
    List<String> drainChunk(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonObject value;
            try {
                value = GsonHolder.GSON.fromJson(trimmed, JsonObject.class);
            } catch (Exception e) {
                continue;
            }
            if (value == null || !value.has("type") || !value.get("type").isJsonPrimitive()) {
                continue;
            }
            String lineType = value.get("type").getAsString();

            if ("assistant".equals(lineType) && value.has("tool_calls")
                    && value.get("tool_calls").isJsonArray()) {
                JsonArray calls = value.getAsJsonArray("tool_calls");
                for (JsonElement el : calls) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject call = el.getAsJsonObject();
                    String name = firstNonBlank(str(call, "name"), functionField(call, "name"), "tool");
                    String inputJson = stringifyToolArguments(resolveRawArguments(call));
                    String toolId = firstNonBlank(str(call, "id"));
                    if (toolId == null) {
                        syntheticCounter += 1;
                        toolId = "grok-tool-" + syntheticCounter;
                    }
                    if (seenStarted.add(toolId)) {
                        out.add(formatToolUseLine(toolId, name, inputJson));
                    }
                }
            } else if ("tool_result".equals(lineType)) {
                String toolId = firstNonBlank(str(value, "tool_call_id"));
                if (toolId == null) {
                    syntheticCounter += 1;
                    toolId = "grok-tool-" + syntheticCounter;
                }
                if (seenCompleted.add(toolId)) {
                    out.add(formatToolResultLine(toolId, truncateResult(stringifyContent(value.get("content")))));
                }
            }
        }
        return out;
    }

    // ── 工具调用参数归一(function.arguments 可为对象或 JSON 字符串) ────────────

    private static String functionField(JsonObject call, String key) {
        JsonObject fn = call != null && call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function") : null;
        return fn == null ? null : str(fn, key);
    }

    /** arguments 原始值:call.arguments 优先,回退 function.arguments。 */
    private static JsonElement resolveRawArguments(JsonObject call) {
        if (call.has("arguments")) {
            return call.get("arguments");
        }
        if (call.has("function") && call.get("function").isJsonObject()) {
            JsonObject fn = call.getAsJsonObject("function");
            if (fn.has("arguments")) {
                return fn.get("arguments");
            }
        }
        return null;
    }

    /** 归一化为对象 JSON 字符串:对象透传;JSON 字符串解析后透传;其余包 {value}/{raw}(对称 JS parseArgs)。 */
    private static String stringifyToolArguments(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return "{}";
        }
        if (raw.isJsonObject()) {
            return GsonHolder.GSON.toJson(raw);
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            String text = raw.getAsString().trim();
            if (!text.isEmpty()) {
                try {
                    JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                    if (parsed != null && parsed.isJsonObject()) {
                        return GsonHolder.GSON.toJson(parsed);
                    }
                    JsonObject wrap = new JsonObject();
                    wrap.add("value", parsed);
                    return GsonHolder.GSON.toJson(wrap);
                } catch (Exception ignored) {
                    // fall through to raw wrap
                }
            }
            JsonObject wrap = new JsonObject();
            wrap.addProperty("raw", text);
            return GsonHolder.GSON.toJson(wrap);
        }
        JsonObject wrap = new JsonObject();
        wrap.add("value", raw);
        return GsonHolder.GSON.toJson(wrap);
    }

    /** 工具结果内容字符串化:null→''、字符串原样、否则美化 JSON(对称 JS stringifyToolResultContent)。 */
    private static String stringifyContent(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            return content.getAsString();
        }
        try {
            return GsonHolder.GSON.toJson(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    private static String truncateResult(String text) {
        if (text == null || text.length() <= MAX_TOOL_RESULT_CHARS) {
            return text == null ? "" : text;
        }
        return text.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n…[truncated " + (text.length() - MAX_TOOL_RESULT_CHARS) + " chars]";
    }

    // ── [MESSAGE] marker 行格式化(Claude 兼容 shape,MarkerCliStreamParser 可解析) ──

    static String formatToolUseLine(String id, String name, String inputJson) {
        return "[MESSAGE] {\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"tool_use\",\"id\":\"" + escapeJson(id)
                + "\",\"name\":\"" + escapeJson(name)
                + "\",\"input\":" + inputJson + "}]}}";
    }

    static String formatToolResultLine(String toolUseId, String content) {
        return "[MESSAGE] {\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"tool_result\",\"tool_use_id\":\"" + escapeJson(toolUseId)
                + "\",\"is_error\":false,\"content\":\"" + escapeJson(content) + "\"}]}}";
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()
                || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
