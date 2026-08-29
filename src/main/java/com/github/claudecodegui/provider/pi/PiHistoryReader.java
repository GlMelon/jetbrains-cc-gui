package com.github.claudecodegui.provider.pi;

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
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Pi CLI 会话历史读取器(session-format v3 公开规范)。
 * <p>
 * 存储布局:{@code ~/.pi/agent/sessions/--<path>--/<timestamp>_<uuid>.jsonl}
 * ({@code <path>} 是 cwd 的 {@code /}→{@code -} 归一,但项目过滤<b>不解析目录名</b>
 * (歧义/平台差异),而是读每个文件首行 session header 的权威 {@code cwd} 字段匹配)。
 * <p>
 * 条目类型(type):session(header)/message(message.role=user|assistant|toolResult,
 * content 块 text|thinking|toolCall)/session_info(name=会话标题,取<b>首个</b>遇到的——
 * 规范语义是"最新",此处为列表性能取首个近似)/compaction/branch_summary/custom 等。
 * <p>
 * v1 简化(有意差异,javadoc 记录):条目树按线性顺序回放,不重建 id/parentId 分支树、
 * 不裁剪 compaction 之前消息——分支场景可能多显示已废弃路径的消息;工具块 start/end
 * 以 assistant.content[].toolCall + toolResult message 配对(天然成对)。
 */
public class PiHistoryReader {

    private static final Logger LOG = Logger.getInstance(PiHistoryReader.class);
    private static final int LIST_SCAN_TITLE_PREVIEW_CHARS = 60;

    /** 测试/非默认根注入(null=默认 ~/.pi/agent)。 */
    private final Path baseOverride;

    public PiHistoryReader() {
        this(null);
    }

    public PiHistoryReader(Path piAgentBase) {
        this.baseOverride = piAgentBase;
    }

    private Path sessionsRoot() {
        Path base = baseOverride != null ? baseOverride : defaultBase();
        return base.resolve("sessions");
    }

    private static Path defaultBase() {
        return Path.of(com.github.claudecodegui.util.PlatformUtils.getHomeDirectory(), ".pi", "agent");
    }

    /** 会话列表(pi.jsonl 全扫:header.cwd 项目匹配 + 首个 session_info 标题)。 */
    public List<PiSessionInfo> listSessions(String projectPath) {
        Path root = sessionsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        String normalizedProject = normalizePath(projectPath);
        List<PiSessionInfo> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> readSessionFile(file, normalizedProject, out));
        } catch (IOException e) {
            LOG.warn("[PiHistory] walk sessions failed: " + e.getMessage());
        }
        out.sort((a, b) -> Long.compare(b.lastTimestamp, a.lastTimestamp));
        return out;
    }

    public Path findSessionFile(String sessionId, String projectPath) {
        for (PiSessionInfo info : listSessions(projectPath)) {
            if (sessionId != null && sessionId.equals(info.sessionId)) {
                // sessionId 即 header.uuid;由调用方按 info 携带的 file 路径操作
                return info.file;
            }
        }
        return null;
    }

    public boolean deleteSession(String sessionId, String projectPath) {
        Path file = findSessionFile(sessionId, projectPath);
        if (file == null) {
            return false;
        }
        try {
            Files.deleteIfExists(file);
            return !Files.exists(file);
        } catch (IOException e) {
            LOG.warn("[PiHistory] delete " + file + " failed: " + e.getMessage());
            return false;
        }
    }

    /** 全量回放 wire 前端消息(线性;忽略 compaction/branch_summary/custom 等)。 */
    public List<JsonObject> loadMessages(Path sessionFile) {
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                JsonObject entry = NativeCliHistoryMessages.parseObject(line);
                if (entry == null || !"message".equals(str(entry, "type"))) {
                    continue;
                }
                JsonObject message = entry.has("message") && entry.get("message").isJsonObject()
                        ? entry.getAsJsonObject("message") : null;
                JsonObject front = message == null ? null : toFrontendMessage(message);
                if (front != null) {
                    out.add(front);
                }
            }
        } catch (IOException e) {
            LOG.warn("[PiHistory] read " + sessionFile + " failed: " + e.getMessage());
        }
        return out;
    }

    // ── 单文件读取 ─────────────────────────────────────────────────────────────

    private void readSessionFile(Path file, String normalizedProject, List<PiSessionInfo> out) {
        String cwd = null;
        String sessionId = null;
        String title = "";
        int messageCount = 0;
        boolean titleResolved = false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                JsonObject entry = NativeCliHistoryMessages.parseObject(line);
                if (entry == null) {
                    continue;
                }
                String type = str(entry, "type");
                if ("session".equals(type)) {
                    cwd = str(entry, "cwd");
                    sessionId = str(entry, "id");
                } else if ("message".equals(type)
                        && entry.has("message") && entry.get("message").isJsonObject()) {
                    messageCount++;
                } else if ("session_info".equals(type) && !titleResolved) {
                    String name = str(entry, "name");
                    if (name != null && !name.isBlank()) {
                        // name 若由首条 prompt 派生会带注入段,剥除(与 kimi 同款)
                        title = truncateTitle(CliPromptContexts.stripInjectedContext(name));
                        titleResolved = true;
                    }
                }
                if (cwd != null && sessionId != null && titleResolved && messageCount > 0) {
                    break; // 前缀信息齐备即止(标题取首见,列表不做全文统计的精确取舍)
                }
            }
        } catch (IOException e) {
            LOG.debug("[PiHistory] scan " + file + " failed: " + e.getMessage());
            return;
        }
        if (sessionId == null) {
            return; // 无 header=非会话文件
        }
        if (normalizedProject != null && !normalizedProject.isEmpty()) {
            if (cwd == null) {
                return; // 无法归位不猜(kimi reader 同一容错立场)
            }
            String n = normalizePath(cwd);
            if (!n.equals(normalizedProject) && !n.startsWith(normalizedProject + "/")) {
                return;
            }
        }
        if (!titleResolved) {
            // 无 session_info 命名:预览留空,前端回退展示(id/时间),不强行截断正文
            title = "";
        }
        PiSessionInfo info = new PiSessionInfo();
        info.file = file;
        info.sessionId = sessionId;
        info.title = title;
        info.cwd = cwd == null ? "" : normalizePath(cwd);
        info.messageCount = messageCount;
        try {
            info.lastTimestamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            info.lastTimestamp = 0L;
        }
        info.firstTimestamp = info.lastTimestamp;
        try {
            info.fileSize = Files.size(file);
        } catch (IOException ignored) {
            // 非关键统计
        }
        out.add(info);
    }

    // ── message → 前端消息(role 三态:user/assistant/toolResult) ───────────────

    static JsonObject toFrontendMessage(JsonObject message) {
        String role = str(message, "role");
        if (role == null) {
            return null;
        }
        switch (role) {
            case "user": {
                JsonArray blocks = new JsonArray();
                String text = extractContent(message.get("content"), blocks, false);
                return NativeCliHistoryMessages.userText(text.isBlank() ? null
                        : CliPromptContexts.stripInjectedContext(text));
            }
            case "assistant": {
                JsonArray blocksOut = new JsonArray();
                StringBuilder text = new StringBuilder();
                JsonElement contentEl = message.get("content");
                if (contentEl != null && contentEl.isJsonArray()) {
                    for (JsonElement el : contentEl.getAsJsonArray()) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject block = el.getAsJsonObject();
                        switch (str(block, "type") == null ? "" : str(block, "type")) {
                            case "text" -> {
                                String t = str(block, "text");
                                if (t != null && !t.isEmpty()) {
                                    text.append(t);
                                    blocksOut.add(NativeCliHistoryMessages.textBlock(t));
                                }
                            }
                            case "thinking" -> {
                                String t = str(block, "thinking");
                                if (t != null && !t.isEmpty()) {
                                    blocksOut.add(NativeCliHistoryMessages.thinkingBlock(t));
                                }
                            }
                            case "toolCall" -> blocksOut.add(NativeCliHistoryMessages.toolUseBlock(
                                    str(block, "id"), str(block, "name"),
                                    block.has("arguments") && block.get("arguments").isJsonObject()
                                            ? block.getAsJsonObject("arguments")
                                            : new JsonObject()));
                            default -> {
                                // image 等暂不入历史回显
                            }
                        }
                    }
                }
                return NativeCliHistoryMessages.assistant(
                        text.length() == 0 ? "" : text.toString(),
                        toList(blocksOut));
            }
            case "toolResult": {
                String callId = str(message, "toolCallId");
                JsonArray sink = new JsonArray();
                String content = extractContent(message.get("content"), sink, true);
                return NativeCliHistoryMessages.toolResultMessage(callId, content,
                        message.has("isError") && message.get("isError").getAsBoolean());
            }
            default:
                return null; // bashExecution/custom/branchSummary/compactionSummary 忽略
        }
    }

    /**
     * content 归一:string 原样;块数组拼 text(Image 仅计占位)。
     *
     * @param collectBlocks 是否把命中的 text/image 占位写入 blocksOut(toolResult 用)
     */
    private static String extractContent(JsonElement contentEl, JsonArray blocksOut,
                                         boolean collectBlocks) {
        if (contentEl == null || contentEl.isJsonNull()) {
            return "";
        }
        if (contentEl.isJsonPrimitive() && contentEl.getAsJsonPrimitive().isString()) {
            return contentEl.getAsString();
        }
        StringBuilder sb = new StringBuilder();
        if (contentEl.isJsonArray()) {
            for (JsonElement el : contentEl.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject block = el.getAsJsonObject();
                String type = str(block, "type");
                if ("text".equals(type)) {
                    String t = str(block, "text");
                    if (t != null) {
                        sb.append(t);
                        if (collectBlocks) {
                            blocksOut.add(NativeCliHistoryMessages.textBlock(t));
                        }
                    }
                } else if ("image".equals(type)) {
                    sb.append("[image]");
                }
            }
        }
        return sb.toString();
    }

    private static List<JsonObject> toList(JsonArray arr) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                out.add(el.getAsJsonObject());
            }
        }
        return out;
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

    private static String truncateTitle(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= LIST_SCAN_TITLE_PREVIEW_CHARS ? trimmed
                : trimmed.substring(0, LIST_SCAN_TITLE_PREVIEW_CHARS) + "…";
    }

    private static String str(JsonObject obj, String key) {
        return obj == null ? null : NativeCliHistoryMessages.primitiveString(obj, key);
    }

    /** 会话列表条目(公有字段,序列化即 wire 形态)。 */
    public static class PiSessionInfo {
        /** 会话 jsonl 文件绝对路径(仅 Java 侧使用,序列化时排除)。 */
        public transient Path file;
        public String sessionId;
        public String title = "";
        public int messageCount;
        public long lastTimestamp;
        public long firstTimestamp;
        public String cwd = "";
        public long fileSize;
    }
}
