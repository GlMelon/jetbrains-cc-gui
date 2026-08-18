package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliPersistentProcess;
import com.github.claudecodegui.cli.common.CliProcessSpec;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.provider.claude.ClaudeCliStreamParser;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * claude 长驻发送路径(设计文档 §4.1/§4.2):与 one-shot 并列的纯辅助层,无自身状态。
 *
 * <p>职责三件事:
 * <ol>
 *   <li>{@link #buildSpec}:组装 {@link CliProcessSpec}(指纹 + spawn 材料)。命令行复用
 *       {@link ClaudeCliSession#buildCommand} 静态版(仅额外加 {@code --input-format stream-json}),
 *       环境由上层按 one-shot 同链构建后传入;</li>
 *   <li>{@link #buildUserMessageLine}:构建 stdin user 消息行
 *       {@code {"type":"user","message":{"role":"user","content":[...]}}}——文本走 text block,
 *       图片附件映射为原生 image content block(base64),prompt 文本仍保留
 *       {@code [Image #N]} 锚点与 Read 指令(transcript 可读性,与 one-shot 等价);</li>
 *   <li>{@link #createTurnContext}:把 {@link ClaudeCliStreamParser} + {@link CliSessionCallback}
 *       适配成 {@link CliPersistentProcess.TurnLineHandler}——result 行产出 {@link SDKResult}
 *       结束本轮,中断标记映射 {@code onInterrupted} 语义。</li>
 * </ol>
 *
 * <p>分流/fallback 编排(何时走长驻、崩溃后何时降级 one-shot)在 {@code ClaudeCliSession.send},
 * 不在此层。
 */
final class ClaudePersistentSendPath {

    private static final Logger LOG = Logger.getInstance(ClaudePersistentSendPath.class);

    private final ClaudeCliSession session;

    ClaudePersistentSendPath(ClaudeCliSession session) {
        this.session = Objects.requireNonNull(session);
    }

    // ── ① CliProcessSpec ──────────────────────────────────────────────────────

    /**
     * 组装长驻进程启动规格。指纹(§4.4)= provider + model + permission-mode + cwd + add-dirs +
     * mcp-config 路径,另含 reasoning-effort(同为命令行参数,影响进程行为,不纳入会语义漂移)。
     */
    CliProcessSpec buildSpec(
            String cliPath,
            CliSendRequest request,
            List<String> addDirs,
            McpGatewayCliConfig gatewayConfig,
            Map<String, String> env
    ) {
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(request.model());
        boolean useGateway = gatewayConfig != null && gatewayConfig.usable();
        boolean hasMcpServers = useGateway || session.mcpConfig().hasServers();
        String mcpConfigFilePath = useGateway
                ? gatewayConfig.configPath().toAbsolutePath().toString()
                : session.mcpConfig().getConfigFilePath();

        String fingerprint = buildFingerprint(request, addDirs, mcpConfigFilePath);
        List<String> command = ClaudeCliSession.buildCommand(
                cliPath, request, addDirs, profile, hasMcpServers, mcpConfigFilePath,
                session.getSessionId(), true);
        LOG.info("[ClaudePersistentSendPath][" + session.tabId() + "] persistent spec: fingerprint="
                + fingerprint + ", cwd=" + request.cwd());
        return new CliProcessSpec(fingerprint, command, env, resolveSpawnCwd(request),
                ClaudePersistentSendPath::buildInterruptRequest);
    }

    private static String buildFingerprint(CliSendRequest request, List<String> addDirs, String mcpConfigFilePath) {
        return String.join("|",
                "claude",
                orDash(request.model()),
                orDash(request.permissionMode()),
                orDash(request.cwd()),
                addDirs == null ? "" : String.join(",", addDirs),
                orDash(mcpConfigFilePath),
                orDash(request.reasoningEffort())
        );
    }

    /**
     * spawn 工作目录:与 one-shot 同规则——cwd 有效用之;缺失/不存在回退用户主目录
     * (Windows CreateProcess 对不存在目录报 ERROR_PATH_NOT_FOUND,须在 spawn 前解析)。
     */
    private static String resolveSpawnCwd(CliSendRequest request) {
        if (request.cwd() != null && !request.cwd().isBlank()) {
            File cwd = new File(request.cwd());
            if (cwd.isDirectory()) {
                return cwd.getAbsolutePath();
            }
            LOG.warn("[ClaudePersistentSendPath][" + request.provider() + "] CWD does not exist, falling back to home: "
                    + request.cwd());
        }
        String home = PlatformUtils.getHomeDirectory();
        return home != null ? home : null;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * interrupt control_request 行(§4.3 V1 实测定稿:subtype 必须嵌在 request 对象内,
     * 顶层放 method 或平铺 subtype 均触发 CLI 解析报错)。provider 协议格式由本 Adapter
     * 独占,{@code CliPersistentProcess} 经 {@code CliProcessSpec.interruptLineSupplier} 取用。
     */
    static String buildInterruptRequest() {
        return "{\"type\":\"control_request\",\"request_id\":\"" + java.util.UUID.randomUUID()
                + "\",\"request\":{\"subtype\":\"interrupt\"}}";
    }

    // ── ② stdin user 消息行 ───────────────────────────────────────────────────

    /**
     * 构建 stream-json 输入的 user 消息行(§4.1)。TEXT 附件已由
     * {@code buildPrompt} 并入 prompt 文本,此处只处理图片:one-shot 模式 CLI 只能经
     * Read 工具读文件,而 stream-json input 原生支持 image content block,直接附上
     * base64 数据让模型即时可见(锚点文本保留,历史回显与 Read 深读仍可用)。
     */
    String buildUserMessageLine(String prompt, List<CliAttachmentHandler.ContentBlock> blocks) {
        JsonArray content = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", prompt != null ? prompt : "");
        content.add(textBlock);

        int imageBlocks = 0;
        for (CliAttachmentHandler.ContentBlock block : blocks) {
            if (block.kind() != CliAttachmentHandler.ContentBlock.Kind.IMAGE) {
                continue;
            }
            JsonObject image = buildImageBlock(block);
            if (image != null) {
                content.add(image);
                imageBlocks++;
            }
        }
        if (imageBlocks > 0) {
            LOG.info("[ClaudePersistentSendPath][" + session.tabId() + "] user message line with "
                    + imageBlocks + " native image block(s)");
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "user");
        wrapper.add("message", message);
        return session.gson().toJson(wrapper);
    }

    /** 图片附件 → 原生 image content block(base64 source,Anthropic Messages API 形态)。 */
    private static JsonObject buildImageBlock(CliAttachmentHandler.ContentBlock block) {
        File file = block.file();
        if (file == null || !file.isFile()) {
            LOG.warn("[ClaudePersistentSendPath] image block without readable file, skipped");
            return null;
        }
        try {
            String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            JsonObject source = new JsonObject();
            source.addProperty("type", CommonConstants.IMAGE_SOURCE_BASE64);
            source.addProperty("media_type", mediaTypeFor(block));
            source.addProperty("data", data);
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.add("source", source);
            return image;
        } catch (Exception e) {
            LOG.warn("[ClaudePersistentSendPath] failed to read image file: " + file, e);
            return null;
        }
    }

    private static String mediaTypeFor(CliAttachmentHandler.ContentBlock block) {
        if (block.mediaType() != null && block.mediaType().startsWith("image/")) {
            return block.mediaType();
        }
        String name = block.file() != null ? block.file().getName().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    // ── ③ 轮事件适配(TurnLineHandler) ────────────────────────────────────────

    /**
     * 轮级时间点埋点(§6.1 补尾):system.init、首个 assistant/stream 事件、首个 text delta
     * 首次到达时各打一条 [CliTurnPerf],使「写入→init」「init→首事件」「首事件→首文本」
     * 可直接从日志归因(此前只有进程级 started/finished,切不出 API 静默期)。
     */
    static final class TurnTimingProbe {
        private final String tabId;
        private final long startNanos = System.nanoTime();
        private boolean initSeen;
        private boolean firstAssistantSeen;
        private boolean firstTextDeltaSeen;

        TurnTimingProbe(String tabId) {
            this.tabId = tabId;
        }

        void record(String line) {
            if (!initSeen && line.contains("\"subtype\":\"init\"")) {
                initSeen = true;
                LOG.info("[CliTurnPerf] system_init: tab=" + tabId
                        + ", sinceTurnStartMs=" + sinceStartMs());
            }
            if (!firstAssistantSeen
                    && (line.contains("\"type\":\"assistant\"") || line.contains("\"type\":\"stream_event\""))) {
                firstAssistantSeen = true;
                LOG.info("[CliTurnPerf] first_assistant_event: tab=" + tabId
                        + ", sinceTurnStartMs=" + sinceStartMs());
            }
            if (!firstTextDeltaSeen && line.contains("\"type\":\"text_delta\"")) {
                firstTextDeltaSeen = true;
                LOG.info("[CliTurnPerf] first_text_delta: tab=" + tabId
                        + ", sinceTurnStartMs=" + sinceStartMs());
            }
        }

        private long sinceStartMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    /** 单轮状态:handler 闭包捕获,上层(ClaudeCliSession fallback 编排)经此读取收尾所需信息。 */
    static final class TurnContext {
        final CliPersistentProcess.TurnLineHandler handler;
        final StringBuilder assistantContent;
        final AtomicBoolean hadError;

        private TurnContext(
                CliPersistentProcess.TurnLineHandler handler,
                StringBuilder assistantContent,
                AtomicBoolean hadError
        ) {
            this.handler = handler;
            this.assistantContent = assistantContent;
            this.hadError = hadError;
        }

        /** 已产出任何输出(决定轮失败收尾时是否带回部分 assistant 内容)。 */
        boolean producedOutput() {
            return assistantContent.length() > 0 || hadError.get();
        }

        String assistantText() {
            return assistantContent.toString();
        }
    }

    /**
     * 为一轮构建逐行处理器(与 one-shot {@code readOutput} 语义逐条对齐):
     * MCP 降级过滤 → parser 分发 → result 行收尾(result 事件即轮完成信号,§4.1)。
     *
     * @param process 长驻进程(session_id 发现后回填元数据,进程面板展示用)
     */
    TurnContext createTurnContext(CliSessionCallback callback, CliPersistentProcess process) {
        Gson gson = session.gson();
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser(gson);
        parser.resetState();
        SDKResult result = new SDKResult();
        StringBuilder diagnostic = new StringBuilder();
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean hadError = new AtomicBoolean(false);

        MessageCallback mcb = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                if (CliConstants.MSG_SESSION_ID.equals(type) && content != null && !content.isBlank()) {
                    session.recordCliSessionId(content);
                    process.updateSessionId(content);
                }
                callback.onMessage(type, content);
            }

            @Override
            public void onError(String error) {
                if (session.handleMcpFailure(error, callback)) {
                    return;
                }
                hadError.set(true);
                callback.onError(error);
            }

            @Override
            public void onComplete(SDKResult r) {
                // 由 TurnLineHandler 在 result 行统一触发
            }
        };

        TurnTimingProbe timingProbe = new TurnTimingProbe(session.tabId());
        CliPersistentProcess.TurnLineHandler handler = (line, interrupted) -> {
            if (line == null || line.isBlank()) {
                return null;
            }
            // MCP 连接失败噪声:降级为非阻塞提示,不污染 diagnostic
            if (session.handleMcpFailure(line, callback)) {
                return null;
            }
            timingProbe.record(line);
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
            parser.parseLine(line, mcb, result, assistantContent, hadError, false);

            // result 事件 = 本轮结束(进程不退出,§4.1)
            if (!ClaudeCliSession.isResultLine(gson, line)) {
                return null;
            }
            String sessionId = session.getSessionId();
            if (sessionId != null) {
                callback.onMessage(CliConstants.MSG_SESSION_ID, sessionId);
            }
            // 被中断轮以 result subtype=error_during_execution 收尾(§4.3 V1 实测),
            // 覆盖为中断语义而非错误。
            if (interrupted || session.isUserInterrupted()) {
                callback.onInterrupted(assistantContent.toString(), CliConstants.I18N_REQUEST_INTERRUPTED);
                return SDKResult.completed(false, assistantContent.toString(), null, true);
            }
            boolean success = !hadError.get() && result.success;
            if (!success) {
                // resume 失败自愈(§4.6):重置 sessionId 使下轮重新开始,与 one-shot 对齐
                session.maybeResetSessionAfterResumeFailure(diagnostic);
            }
            callback.onComplete(success, success ? assistantContent.toString() : null, success ? null : result.error);
            return SDKResult.completed(success, assistantContent.toString(), result.error, false);
        };

        return new TurnContext(handler, assistantContent, hadError);
    }
}
