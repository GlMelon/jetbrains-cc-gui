package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * kimi ACP 流解析器:将 {@code session/update} 通知映射为统一 wire 事件 + 重放门控。
 *
 * <p>实现 {@link CliStreamParser} 契约,由 {@link KimiAcpCliSession} 持有。
 * {@link KimiAcpConnection#route} 把无 id 的通知行转交 {@link #parseLine}。
 *
 * <h3>update→MSG 映射(对齐既有 provider 思考/工具链路)</h3>
 * <ul>
 *   <li>{@code agent_thought_chunk} → {@link CliSectionEmitter#thinkingStart()}(仅首次) +
 *       {@link CliSectionEmitter#thinkingDelta(String)}(纯增量,对齐
 *       {@code GrokCliStreamParser.handleThought()} 的增量式机制;stream-json 通道不透出 thinking,
 *       ACP 通道是其一等公民出口);</li>
 *   <li>{@code agent_message_chunk} → {@link CliSectionEmitter#contentDelta(StringBuilder, String)};</li>
 *   <li>{@code tool_call} → {@link CliSectionEmitter#toolUse(JsonObject)}(懒创建去重:同 toolCallId 只发一次);</li>
 *   <li>{@code tool_call_update} → status 为 completed/failed 时发
 *       {@link CliSectionEmitter#toolResult(JsonObject)}(content 为 REPLACE 全量文本,非追加);</li>
 *   <li>{@code session_info_update} → 捕获 title(供会话层接入 CliSessionTitleService);</li>
 *   <li>{@code available_commands_update}/{@code user_message_chunk} → 忽略
 *       (load 重放时的 user_message_chunk 天然静默)。</li>
 * </ul>
 *
 * <h3>重放门控(live)</h3>
 * {@code session/load} 会重放历史 chunk(kimi 0.38 v2 引擎当前实测不重放,但防御性保留)。
 * 门控默认 {@code false}:load 重放期间除 sessionId 外全部丢弃;会话层在 {@code session/prompt}
 * 发出前调 {@link #beginLiveTurn()} 开启,此后 update 才进 UI(镜像 upstream grok 的
 * {@code liveStreaming} 方案,防止 session/load 与重放污染本轮 UI)。
 */
public class KimiAcpStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(KimiAcpStreamParser.class);

    private final CliSectionEmitter emitter;
    private final StringBuilder assistantContent = new StringBuilder();

    private String capturedSessionId;
    private String capturedTitle;
    private boolean hasError;
    private boolean receivedAnyEvent;
    private boolean streamEnded; // 恒 false,由会话层补发
    private String errorDiagnostic;

    /** 重放门控:默认 false(load 重放期间 update 全丢弃),beginLiveTurn() 后放行。 */
    private boolean live;
    private boolean thinkingActivated;

    /** tool_call 懒创建去重:同 toolCallId 只发一次 tool_use。 */
    private final Set<String> seenToolCallIds = new HashSet<>();
    /** tool_call title 缓存:供 tool_call_update 的 tool_result 关联(暂未直接用,保留扩展)。 */
    private final Map<String, String> toolCallNames = new HashMap<>();

    public KimiAcpStreamParser(CliSessionCallback callback) {
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    /**
     * 注入捕获的 sessionId(从 session/new|load 响应里由会话层调用)。
     * 不受 live 门控(sessionId 总要透传)。
     */
    void attachSessionId(String id) {
        if (id != null && !id.isBlank()) {
            capturedSessionId = id;
            emitter.sessionId(id);
        }
    }

    /** 开启重放门控:此后 update 才进 UI。会话层在 session/prompt 发出前调用。 */
    void beginLiveTurn() {
        live = true;
    }

    /** 捕获的会话标题(供会话层接入 CliSessionTitleService)。 */
    String capturedTitle() {
        return capturedTitle;
    }

    @Override
    public String capturedSessionId() {
        return capturedSessionId;
    }

    @Override
    public String accumulatedText() {
        return assistantContent.toString();
    }

    @Override
    public boolean hasError() {
        return hasError;
    }

    @Override
    public boolean receivedAnyEvent() {
        return receivedAnyEvent;
    }

    @Override
    public boolean streamEnded() {
        return streamEnded;
    }

    @Override
    public String errorDiagnostic() {
        return errorDiagnostic;
    }

    /**
     * 逐行解析 session/update 通知(由 KimiAcpConnection.route 转发)。
     * 外层:{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"...","update":{...}}}
     */
    @Override
    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                    ? msg.getAsJsonObject("params") : null;
            if (params == null || !params.has("update")) {
                return;
            }
            JsonObject update = params.getAsJsonObject("update");
            handleUpdate(update);
        } catch (Exception e) {
            hasError = true;
            errorDiagnostic = "parse error: " + e.getMessage();
            LOG.warn("[KimiAcpStreamParser] parseLine failed: " + line.substring(0, Math.min(line.length(), 200)), e);
        }
    }

    private void handleUpdate(JsonObject update) {
        if (update == null || !update.has("sessionUpdate")) {
            return;
        }
        String kind = update.get("sessionUpdate").getAsString();

        // 重放门控:live=false 时全部丢弃(load 重放期间的 update)
        if (!live) {
            return;
        }

        receivedAnyEvent = true;
        try {
            switch (kind) {
                case KimiAcpProtocol.UPDATE_AGENT_THOUGHT_CHUNK -> handleThoughtChunk(update);
                case KimiAcpProtocol.UPDATE_AGENT_MESSAGE_CHUNK -> handleMessageChunk(update);
                case KimiAcpProtocol.UPDATE_TOOL_CALL -> handleToolCall(update);
                case KimiAcpProtocol.UPDATE_TOOL_CALL_UPDATE -> handleToolCallUpdate(update);
                case KimiAcpProtocol.UPDATE_SESSION_INFO -> handleSessionInfo(update);
                case KimiAcpProtocol.UPDATE_AVAILABLE_COMMANDS,
                     KimiAcpProtocol.UPDATE_USER_MESSAGE_CHUNK -> {
                    // 忽略
                }
                default -> {
                    // 未知变体,忽略
                }
            }
        } catch (Exception e) {
            hasError = true;
            errorDiagnostic = "handleUpdate error (" + kind + "): " + e.getMessage();
            LOG.warn("[KimiAcpStreamParser] handleUpdate failed: " + kind, e);
        }
    }

    /** agent_thought_chunk:思考增量(纯 delta,对齐 GrokCliStreamParser.handleThought)。 */
    private void handleThoughtChunk(JsonObject update) {
        String text = extractContentText(update);
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!thinkingActivated) {
            emitter.thinkingStart();
            thinkingActivated = true;
        }
        emitter.thinkingDelta(text);
    }

    /** agent_message_chunk:正文增量。 */
    private void handleMessageChunk(JsonObject update) {
        String text = extractContentText(update);
        if (text == null || text.isEmpty()) {
            return;
        }
        emitter.contentDelta(assistantContent, text);
    }

    /**
     * tool_call:懒创建去重(同 toolCallId 只发一次)。
     * name 提取优先级:rawInput.toolName > name > toolName > title > kind(兜底)。
     */
    private void handleToolCall(JsonObject update) {
        String tcId = getStringField(update, "toolCallId");
        if (tcId == null || tcId.isBlank()) {
            return;
        }
        if (seenToolCallIds.contains(tcId)) {
            return;
        }
        seenToolCallIds.add(tcId);

        String name = extractToolName(update);
        toolCallNames.put(tcId, name);

        JsonObject input = update.has("rawInput") && update.get("rawInput").isJsonObject()
                ? update.getAsJsonObject("rawInput") : null;
        if (input == null) {
            input = new JsonObject();
            String title = getStringField(update, "title");
            if (title != null) {
                input.addProperty("title", title);
            }
        }

        JsonObject toolUse = new JsonObject();
        // 前端 contentBlockNormalize 按 block.type 分派,缺 type 的块会被静默丢弃
        toolUse.addProperty("type", CommonConstants.BLOCK_TYPE_TOOL_USE);
        toolUse.addProperty("id", tcId);
        toolUse.addProperty("name", name);
        toolUse.add("input", input);
        emitter.toolUse(toolUse);
    }

    /**
     * tool_call_update:status 为 completed/failed 时发 tool_result。
     * content 为 REPLACE 语义(累积替换,取全量文本)。
     */
    private void handleToolCallUpdate(JsonObject update) {
        String tcId = getStringField(update, "toolCallId");
        if (tcId == null || tcId.isBlank()) {
            return;
        }
        String status = getStringField(update, "status");
        if (!"completed".equals(status) && !"failed".equals(status)) {
            // in_progress 等中间态不渲染(与其它 provider 一致)
            return;
        }
        String resultText = extractToolResultText(update);
        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", CommonConstants.BLOCK_TYPE_TOOL_RESULT);
        toolResult.addProperty("tool_use_id", tcId);
        toolResult.addProperty("is_error", "failed".equals(status));
        toolResult.addProperty("content", resultText != null ? resultText : "");
        emitter.toolResult(toolResult);
    }

    /** session_info_update:捕获 title(受 live 门控)。 */
    private void handleSessionInfo(JsonObject update) {
        String title = getStringField(update, "title");
        if (title != null && !title.isBlank()) {
            capturedTitle = title;
        }
    }

    // ── 字段提取工具 ─────────────────────────────────────────────────────────

    /** update.content:{type:"text",text:"..."} → 取 text(增量 delta)。 */
    private static String extractContentText(JsonObject update) {
        if (!update.has("content") || !update.get("content").isJsonObject()) {
            return null;
        }
        JsonObject content = update.getAsJsonObject("content");
        return getStringField(content, "text");
    }

    /** tool_call 的 name 提取(优先级链 + 兜底 kind)。 */
    private static String extractToolName(JsonObject update) {
        if (update.has("rawInput") && update.get("rawInput").isJsonObject()) {
            JsonObject raw = update.getAsJsonObject("rawInput");
            String n = firstNonBlank(
                    getStringField(raw, "toolName"),
                    getStringField(raw, "name"),
                    getStringField(raw, "tool_name"));
            if (n != null) {
                return n;
            }
        }
        String n = firstNonBlank(
                getStringField(update, "name"),
                getStringField(update, "toolName"),
                getStringField(update, "title"));
        if (n != null) {
            return n;
        }
        // kind 兜底(execute/read/edit 等,转可读名)
        String kind = getStringField(update, "kind");
        return kind != null ? kind : "tool";
    }

    /**
     * tool_call_update.content 数组取所有 type=content 的 content.text 拼接(REPLACE 全量)。
     * 形如:[{type:"content",content:{type:"text",text:"..."}}]
     */
    private static String extractToolResultText(JsonObject update) {
        if (!update.has("content") || !update.get("content").isJsonArray()) {
            // 也可能 rawOutput 字段
            String rawOutput = getStringField(update, "rawOutput");
            return rawOutput;
        }
        JsonArray arr = update.getAsJsonArray("content");
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject item = el.getAsJsonObject();
            if (!"content".equals(getStringField(item, "type"))) {
                continue;
            }
            if (item.has("content") && item.get("content").isJsonObject()) {
                JsonObject inner = item.getAsJsonObject("content");
                String text = getStringField(inner, "text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String getStringField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
