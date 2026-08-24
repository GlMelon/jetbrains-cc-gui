package com.github.claudecodegui.service;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.PromptEnhancerProcessRunner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lightweight AI service for commit message generation.
 * Spawns channel-manager.js directly to send prompts, without SDK Bridge dependency.
 * Supports both Claude and Codex providers.
 *
 * <p>Process lifecycle (async stdout drain, hard timeout, exit-code + fatal-error-line
 * propagation) is delegated to {@link PromptEnhancerProcessRunner} — see its javadoc for
 * why a synchronous {@code readLine()} loop makes {@code waitFor(timeout)} unenforceable.
 */
public class CommitMessageAiService {

    private static final Logger LOG = Logger.getInstance(CommitMessageAiService.class);
    private static final Gson gson = new GsonBuilder().create();
    private static final int TIMEOUT_SECONDS = 120;
    private static final long READER_DRAIN_SECONDS = 5;

    private final NodeService nodeService;

    public CommitMessageAiService() {
        this.nodeService = NodeService.getInstance();
    }

    /**
     * Send a prompt to the AI provider and collect the response.
     */
    public CompletableFuture<String> sendPrompt(String provider, String prompt, String cwd, String model) {
        // 显式 executor:内部 spawn channel-manager.js 子进程并阻塞至完成(TIMEOUT_SECONDS=120s),
        // 无 executor 会落在 ForkJoinPool.commonPool(大小≈CPU核数-1),commit 高频路径并发几次就
        // 耗尽并行度,且与平台其余 commonPool 使用者互相拖累。
        return CompletableFuture.supplyAsync(() -> {
            try {
                String node = nodeService.getNodeDetector().findNodeExecutable();
                File bridgeDir = nodeService.getSdkTestDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    throw new RuntimeException("Bridge directory not ready");
                }

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(new File(bridgeDir, "channel-manager.js").getAbsolutePath());
                command.add(provider);
                command.add("send");

                JsonObject stdin = new JsonObject();
                stdin.addProperty("message", prompt);
                stdin.addProperty("cwd", cwd != null ? cwd : "");
                if (model != null) {
                    stdin.addProperty("model", model);
                }
                if (CommonConstants.PROVIDER_CLAUDE.equals(provider)) {
                    stdin.addProperty("streaming", false);
                    stdin.addProperty("disableThinking", true);
                    stdin.addProperty("sessionId", "");
                    stdin.addProperty("permissionMode", "");
                } else {
                    stdin.addProperty("threadId", "");
                    stdin.addProperty("permissionMode", "");
                    stdin.addProperty("reasoningEffort", "medium");
                }
                String stdinJson = gson.toJson(stdin);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(bridgeDir);
                pb.redirectErrorStream(true);

                nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);
                pb.environment().put(CommonConstants.PROVIDER_CODEX.equals(provider)
                        ? "CODEX_USE_STDIN" : "CLAUDE_USE_STDIN", "true");

                StringBuilder content = new StringBuilder();
                StringBuilder output = new StringBuilder();
                AtomicReference<String> fatalError = new AtomicReference<>();

                Consumer<String> lineHandler = line -> {
                    output.append(line).append("\n");
                    if (line.startsWith("[CONTENT]")) {
                        content.append(line.substring("[CONTENT]".length()).trim());
                    } else if (line.startsWith("[MESSAGE]")) {
                        try {
                            JsonObject msg = gson.fromJson(
                                    line.substring("[MESSAGE]".length()).trim(), JsonObject.class);
                            if (msg != null && msg.has("type")
                                    && "assistant".equals(msg.get("type").getAsString())
                                    && msg.has("content")) {
                                content.append(msg.get("content").getAsString());
                            }
                        } catch (Exception ignored) {
                        }
                    } else if (line.startsWith("[SEND_ERROR]")) {
                        String errorJson = line.substring("[SEND_ERROR]".length()).trim();
                        String errorMsg;
                        try {
                            JsonObject err = gson.fromJson(errorJson, JsonObject.class);
                            errorMsg = (err != null && err.has("error"))
                                    ? err.get("error").getAsString() : errorJson;
                        } catch (Exception e) {
                            errorMsg = errorJson;
                        }
                        fatalError.compareAndSet(null, "AI service error: " + errorMsg);
                    } else if (line.startsWith("[UNCAUGHT_ERROR]")
                            || line.startsWith("[STARTUP_ERROR]")
                            || line.startsWith("[COMMAND_ERROR]")) {
                        // node 侧致命错误:原 BaseSDKBridge.drainOutput 将这些前缀视作致命,
                        // 迁移后须继续识别,否则启动/未捕获异常会被误报为 "empty message"。
                        fatalError.compareAndSet(null, "AI service fatal error: " + line);
                    }
                };

                int exitCode = PromptEnhancerProcessRunner.runWithProcessManager(
                        pb,
                        nodeService.getProcessManager(),
                        "commit-ai",
                        stdinJson,
                        TIMEOUT_SECONDS,
                        READER_DRAIN_SECONDS,
                        lineHandler);

                // 致命错误行优先(明确的服务端报错),覆盖 exit code 判定
                if (fatalError.get() != null) {
                    throw new RuntimeException(fatalError.get());
                }
                // 非零退出且无致命错误行 → 报 exit code,避免异常退出被掩盖为 "empty message"
                if (exitCode != 0) {
                    throw new RuntimeException("AI service exited with code " + exitCode);
                }

                String result = content.toString().trim();
                if (result.isEmpty()) {
                    LOG.warn("[CommitMessageAiService] No content collected, checking fallback output");
                    String lastJson = extractLastJson(output.toString());
                    if (lastJson != null) {
                        try {
                            JsonObject jsonResult = gson.fromJson(lastJson, JsonObject.class);
                            if (jsonResult != null && jsonResult.has("content")) {
                                result = jsonResult.get("content").getAsString().trim();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("AI service call failed: " + e.getMessage(), e);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private String extractLastJson(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        return null;
    }
}
