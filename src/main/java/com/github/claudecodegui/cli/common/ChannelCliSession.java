package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用 ai-bridge channel CLI 会话:spawn {@code node channel-manager.js <provider> send},
 * 经 stdin 传入 JSON 请求,stdout 返回 marker 事件流(由 {@link MarkerCliStreamParser} 解析)。
 * <p>
 * 用于不直接 spawn provider 二进制的 provider(如 DSH 的 host RPC、OMP 的 NDJSON→marker 转换),
 * 复用 ai-bridge 侧已实现的完整协议转换逻辑。与 {@code PiCliSession} 等直 spawn 模式互补:
 * 直 spawn 用于 CLI 二进制输出即为 marker 的 provider(pi/grok/kimi/opencode);
 * channel 模式用于 ai-bridge 需做格式转换的 provider(omp/dsh)。
 * <p>
 * stdin/stdout 并发:stdin writer 线程写完 JSON 即关闭(触发 ai-bridge 读到 EOF 完成请求);
 * 同时 {@link CliProcessLifecycle#drainAsync} 排空 stdout 防 pipe 满死锁。
 */
public class ChannelCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(ChannelCliSession.class);

    private final String tabId;
    private final ProviderType providerType;
    private final NodeService nodeService;
    private final Gson gson = GsonHolder.GSON;
    private final MarkerCliStreamParserFactory parserFactory;

    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);

    /** Stream parser 工厂:不同 provider 共用 {@link MarkerCliStreamParser},按需可特化。 */
    @FunctionalInterface
    public interface MarkerCliStreamParserFactory {
        MarkerCliStreamParser create(CliSessionCallback callback);
    }

    public ChannelCliSession(String tabId, ProviderType providerType, NodeService nodeService) {
        this(tabId, providerType, nodeService, MarkerCliStreamParser::new);
    }

    public ChannelCliSession(String tabId, ProviderType providerType, NodeService nodeService,
                             MarkerCliStreamParserFactory parserFactory) {
        this.tabId = tabId;
        this.providerType = providerType;
        this.nodeService = nodeService;
        this.parserFactory = parserFactory;
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        return CliSessionExecutor.runAsync(() -> {
            StringBuilder diagnostic = new StringBuilder();
            try {
                runOnce(request, callback, diagnostic);
            } catch (Exception | LinkageError e) {
                // 同 AbstractRunOnceCliSession:LinkageError 须按 turn 失败收尾,防静默穿透。
                LOG.warn("[" + providerType.value() + "CliSession][" + tabId + "] send failed", e);
                if (wasInterrupted()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError(providerType.displayLabel(), e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                }
            } finally {
                activeHandle = null;
                userInterrupted.set(false);
            }
        });
    }

    private void runOnce(CliSendRequest request, CliSessionCallback callback, StringBuilder diagnostic) throws Exception {
        MarkerCliStreamParser parser = parserFactory.create(callback);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.MCP_SYNCING.value());
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());

        String node = nodeService.getNodeDetector().findNodeExecutable();
        if (node == null) {
            String err = CliErrorFormatter.formatError(providerType.displayLabel(), "Node.js not found");
            callback.onError(err);
            callback.onComplete(false, null, err);
            return;
        }
        File bridgeDir = nodeService.getBridgeDir();
        File channelManager = new File(bridgeDir, "channel-manager.js");
        if (!channelManager.isFile()) {
            String err = CliErrorFormatter.formatError(providerType.displayLabel(),
                    "channel-manager.js not found at " + channelManager.getAbsolutePath());
            callback.onError(err);
            callback.onComplete(false, null, err);
            return;
        }

        // 命令:node channel-manager.js <provider> send
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(node);
        cmd.add(channelManager.getAbsolutePath());
        cmd.add(providerType.value());
        cmd.add("send");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> cliEnv = pb.environment();
        cliEnv.clear();
        cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
        cliEnv.put(CliConstants.ARG_NO_COLOR, "1");
        // 启用 stdin JSON 读取(ai-bridge stdin-utils 按 <PROVIDER>_USE_STDIN 判定)
        cliEnv.put(stdinEnvKey(providerType), "true");
        CliEnvironmentBuilder.configureProjectPath(cliEnv, request.cwd());
        CliEnvironmentBuilder.applyExtraEnv(cliEnv, request.extraEnv());
        nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);

        if (request.cwd() != null && !request.cwd().isBlank()) {
            File cwd = new File(request.cwd());
            if (cwd.isDirectory()) {
                pb.directory(cwd);
            }
        }

        // stdin JSON:ai-bridge channel 的 send 命令从 stdin 读请求字段
        byte[] stdinJson = buildStdinJson(request);

        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.UNDERSTANDING.value());
        Process process = pb.start();
        CliProcessHandle currentHandle = new CliProcessHandle(process, providerType.value() + "-tab-" + tabId);
        activeHandle = currentHandle;

        try {
            if (userInterrupted.get()) {
                currentHandle.interrupt();
            }
            // stdin writer:写完即关闭,触发 ai-bridge stdin EOF
            CompletableFuture<Void> stdinWriter = CliSessionExecutor.runAsync(() -> {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinJson);
                    os.flush();
                } catch (Exception ignored) {
                    // 进程可能已退出/被中断,忽略
                }
            });
            CompletableFuture<Void> outputDrain = CliProcessLifecycle.drainAsync(process, () -> {
                try (InputStream rawIn = process.getInputStream()) {
                    CliOutputLimits.LineBuffer lineBuf = new CliOutputLimits.LineBuffer();
                    byte[] readBuf = new byte[8192];
                    int n;
                    while ((n = rawIn.read(readBuf)) != -1) {
                        for (int i = 0; i < n; i++) {
                            byte b = readBuf[i];
                            if (b == '\n') {
                                processLine(lineBuf, parser, diagnostic);
                            } else {
                                lineBuf.write(b);
                            }
                        }
                    }
                    if (lineBuf.size() > 0) {
                        processLine(lineBuf, parser, diagnostic);
                    }
                }
            });

            CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(process, outputDrain);
            try {
                stdinWriter.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // stdin writer 超时未结束(进程已退出/被中断),忽略
            }
            int exitCode = outcome.exitCode();

            if (outcome.timedOut() && !wasInterrupted()) {
                String err = CliErrorFormatter.formatError(providerType.displayLabel(), "CLI request timed out");
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return;
            }
            if (wasInterrupted()) {
                callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
                return;
            }

            if (parser.capturedSessionId() != null) {
                // channel 会话的 sessionId 续接由 ai-bridge 侧管理,此处仅透传
            }

            if (exitCode == 0 && !parser.hasError()) {
                if (!parser.streamEnded()) {
                    if (!parser.receivedAnyEvent()) {
                        String err = CliErrorFormatter.formatError(providerType.displayLabel(),
                                "进程退出但未返回任何事件(exit=0)。检查 ai-bridge channel-manager.js 与 "
                                        + providerType.value() + " 服务配置。");
                        callback.onError(err);
                        callback.onComplete(false, parser.accumulatedText(), err);
                        return;
                    }
                    callback.onMessage(CliConstants.MSG_STREAM_END, "");
                    callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                }
                callback.onComplete(true, parser.accumulatedText(), null);
                return;
            }

            String errDiag = parser.errorDiagnostic();
            if (errDiag == null || errDiag.isEmpty()) {
                errDiag = diagnostic.toString();
            }
            String err = errDiag.isBlank()
                    ? CliErrorFormatter.formatExitError(providerType.displayLabel(), exitCode, diagnostic)
                    : CliErrorFormatter.formatError(providerType.displayLabel(), errDiag);
            callback.onError(err);
            callback.onComplete(false, parser.accumulatedText(), err);
        } finally {
            CliProcessLifecycle.terminate(process);
            if (activeHandle == currentHandle) {
                activeHandle = null;
            }
        }
    }

    private byte[] buildStdinJson(CliSendRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("message", request.message());
        if (request.sessionId() != null) {
            json.addProperty("sessionId", request.sessionId());
        }
        if (request.cwd() != null) {
            json.addProperty("cwd", request.cwd());
        }
        String model = request.actualModel() != null ? request.actualModel() : request.model();
        if (model != null) {
            json.addProperty("model", model);
        }
        if (request.permissionMode() != null) {
            json.addProperty("permissionMode", request.permissionMode());
        }
        if (request.reasoningEffort() != null) {
            json.addProperty("reasoningEffort", request.reasoningEffort());
        }
        if (request.agentPrompt() != null) {
            json.addProperty("agentPrompt", request.agentPrompt());
        }
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            json.add("attachments", gson.toJsonTree(request.attachments()));
        }
        if (request.openedFiles() != null && !request.openedFiles().isJsonNull()) {
            json.add("openedFiles", request.openedFiles());
        }
        if (!request.fileTagPaths().isEmpty()) {
            json.add("fileTagPaths", gson.toJsonTree(request.fileTagPaths()));
        }
        // dshPreset 由上游 SessionState 注入 request.env() 或单独透传;此处不直接处理(provider 特化)
        return gson.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static String stdinEnvKey(ProviderType providerType) {
        return providerType.value().toUpperCase(java.util.Locale.ROOT) + "_USE_STDIN";
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        CliProcessHandle h = activeHandle;
        if (h != null) {
            h.interrupt();
        }
    }

    @Override
    public void dispose() {
        interrupt();
    }

    private boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }

    private void processLine(CliOutputLimits.LineBuffer lineBuf, MarkerCliStreamParser parser, StringBuilder diagnostic) {
        if (lineBuf.isTruncated()) {
            lineBuf.reset();
            throw new IllegalStateException("CLI stdout line exceeded " + CliOutputLimits.MAX_LINE_BYTES + " bytes");
        }
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return;
        }
        String line = new String(bytes, 0, len, StandardCharsets.UTF_8);
        if (line.isBlank()) {
            return;
        }
        parser.parseLine(line);
        if (!line.trim().startsWith("[")) {
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
        }
    }
}
