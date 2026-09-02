package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.provider.dsh.DshEnvSupport;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CLI provider 动态模型目录(kimi / grok / pi / omp / dsh / codex)。
 *
 * <p>spawn 一次性进程 {@code channel-manager.js <provider> listModels},解析 stdout JSON,
 * 经 {@code window.setCliModels(payload)} 回填前端 useCliModels。前端以 legacy
 * {@code sendBridgeEvent('get_cli_models', provider)} 请求(payload 即 provider id)。
 *
 * <p>定位(与 MODEL_REGISTRY 的分工):claude / codex / opencode 的静态模型经
 * {@code ReadOnlyDefaultModels} 注入 MODEL_REGISTRY 下发;CLI-only provider
 * (grok/kimi/pi)无 registry 条目来源,本链路是其唯一模型目录——1c1084b3 handler
 * 体系合并时被删的 {@code CliModelsHandler} 语义在此按 FrontendActionHandler 架构恢复。
 */
public final class GetCliModelsActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetCliModelsActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;
    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final long TIMEOUT_SECONDS = 50L;
    /** Cap on captured stdout — a model list is small; this stops memory exhaustion. */
    private static final int MAX_OUTPUT_CHARS = 64_000;

    /** 与前端 useCliModels#supportsDynamicModels 对齐(codex + CLI-only providers)。 */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "opencode", "kimi", "pi", "omp", "codex", "grok", "dsh");

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CLI_MODELS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        String provider = payload != null ? payload.trim().toLowerCase(Locale.ROOT) : "";
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            pushError(context.handlerContext(), provider,
                    "Unsupported CLI provider for model list: " + provider);
            return;
        }
        // spawn + waitFor 阻塞 → 后台线程;callJavaScript 自带 EDT 切换
        CompletableFuture.runAsync(() -> listModels(context.handlerContext(), provider),
                AppExecutorUtil.getAppExecutorService());
    }

    private void listModels(HandlerContext ctx, String provider) {
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        try {
            NodeService nodeService = NodeService.getInstance();
            NodeDetector nodeDetector = nodeService.getNodeDetector();
            EnvironmentConfigurator envConfigurator = nodeService.getEnvConfigurator();
            String node = nodeDetector.findNodeExecutable();
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null || !bridgeDir.exists()) {
                pushError(ctx, provider, "Bridge directory not ready");
                return;
            }

            File script = new File(bridgeDir, CHANNEL_SCRIPT);
            if (!script.exists()) {
                pushError(ctx, provider, "channel-manager.js not found");
                return;
            }

            List<String> command = new ArrayList<>(NodeDetector.buildNodeScriptCommand(
                    node, script.getAbsolutePath()));
            command.add(provider);
            command.add("listModels");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            envConfigurator.updateProcessEnvironment(pb, node);
            if ("dsh".equals(provider)) {
                // DSH model catalog comes from the live host — honor the
                // configured origin so the picker reflects the actual server.
                DshEnvSupport.inject(env, CodemossSettingsService.getInstance());
            }

            LOG.info("[CliModels] Listing models for " + provider + ": " + String.join(" ", command));

            process = pb.start();
            processManager = NodeService.getInstance().getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            if (processToken == null) {
                pushError(ctx, provider, "Model listing cancelled during shutdown");
                return;
            }
            // Drain stdout on a daemon thread (bounded) so a verbose child cannot
            // deadlock on a full pipe buffer while this thread enforces the timeout.
            StringBuilder output = new StringBuilder();
            Process finalProcess = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                pushError(ctx, provider, "Timed out listing " + provider + " models");
                return;
            }
            // Process exited; the reader hits EOF promptly — join for the final lines.
            readerThread.join(2000L);

            JsonObject payload = extractJsonObject(output.toString());
            if (payload == null) {
                pushError(ctx, provider, "No model list JSON in " + provider + " listModels output");
                return;
            }
            if (payload.has("debug") && payload.get("debug").isJsonObject()) {
                // Bridge-side diagnostics (e.g. empty model parse, fallback source)
                LOG.warn("[CliModels] " + provider + " listModels debug: " + payload.get("debug"));
            }
            if (!payload.has("provider") || payload.get("provider").isJsonNull()) {
                payload.addProperty("provider", provider);
            }
            ctx.callJavaScript("window.setCliModels", ctx.escapeJs(GSON.toJson(payload)));
        } catch (Exception e) {
            LOG.warn("[CliModels] Failed for " + provider + ": " + e.getMessage(), e);
            pushError(ctx, provider, e.getMessage() != null ? e.getMessage() : "list models failed");
        } finally {
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private JsonObject extractJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // Prefer last JSON object line (channel-manager may print diagnostics to stdout).
        String[] lines = raw.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) {
                continue;
            }
            try {
                JsonObject obj = GSON.fromJson(line, JsonObject.class);
                if (obj != null && (obj.has("models") || obj.has("success"))) {
                    return obj;
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback: whole buffer
        try {
            int start = raw.lastIndexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return GSON.fromJson(raw.substring(start, end + 1), JsonObject.class);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 失败也走 setCliModels(success:false),前端据此保底并展示 error。 */
    private void pushError(HandlerContext ctx, String provider, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("provider", provider != null ? provider : "");
        error.addProperty("error", message != null ? message : "unknown error");
        error.add("models", GSON.toJsonTree(new ArrayList<String>()));
        ctx.callJavaScript("window.setCliModels", ctx.escapeJs(GSON.toJson(error)));
    }
}
