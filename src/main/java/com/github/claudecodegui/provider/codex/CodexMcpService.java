package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.protocol.CodexProtectedEnvKey;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Independent MCP server tools query service for Codex provider.
 * Extracts getMcpServerTools using NodeService
 * for Node.js infrastructure.
 */
public class CodexMcpService {

    private static final Logger LOG = Logger.getInstance(CodexMcpService.class);
    private static final long MCP_TOOLS_TIMEOUT_MS = 65_000;
    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final int MAX_ENV_VAR_VALUE_LENGTH = 16 * 1024;
    private static final Set<String> PROTECTED_ENV_KEYS = new HashSet<>();

    static {
        // 基础保护变量(A5 SSOT:CodexProtectedEnvKey 枚举,与 CodexCliCommandUtils / 前端生成链同源)
        for (CodexProtectedEnvKey key : CodexProtectedEnvKey.values()) {
            PROTECTED_ENV_KEYS.add(key.value());
        }
        // code-injection / library-injection 防护:与运行时无关,任何 spawn 用户自定义 env
        // 的子进程都须拦截。对齐原 Codex SDK 路径的 32 key 保护集(等价迁移)。
        PROTECTED_ENV_KEYS.add("NODE_OPTIONS");
        PROTECTED_ENV_KEYS.add("NODE_EXTRA_CA_CERTS");
        PROTECTED_ENV_KEYS.add("ELECTRON_RUN_AS_NODE");
        PROTECTED_ENV_KEYS.add("LD_PRELOAD");
        PROTECTED_ENV_KEYS.add("LD_LIBRARY_PATH");
        PROTECTED_ENV_KEYS.add("LD_AUDIT");
        PROTECTED_ENV_KEYS.add("DYLD_INSERT_LIBRARIES");
        PROTECTED_ENV_KEYS.add("DYLD_LIBRARY_PATH");
        PROTECTED_ENV_KEYS.add("DYLD_FRAMEWORK_PATH");
        PROTECTED_ENV_KEYS.add("BASH_ENV");
        PROTECTED_ENV_KEYS.add("PERL5LIB");
        PROTECTED_ENV_KEYS.add("PYTHONPATH");
        PROTECTED_ENV_KEYS.add("GIT_SSH_COMMAND");
        PROTECTED_ENV_KEYS.add("GIT_EXTERNAL_DIFF");
    }

    private final NodeService nodeService;
    private final CodemossSettingsService settingsService;
    private final Gson gson;

    public CodexMcpService() {
        this.nodeService = NodeService.getInstance();
        this.settingsService = CodemossSettingsService.getInstance();
        this.gson = new GsonBuilder().create();
    }

    public CompletableFuture<JsonObject> getMcpServerTools(String serverId, JsonObject serverConfig) {
        return CompletableFuture.supplyAsync(() -> {
            String channelId = ProcessManager.newChannelId("__codex_mcp_tools__");
            Process process = null;
            long startTime = System.currentTimeMillis();
            LOG.info("[CodexMcpTools] Starting getMcpServerTools, serverId=" + serverId);

            try {
                String node = nodeService.getNodeDetector().findNodeExecutable();
                File bridgeDir = nodeService.getSdkTestDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("serverId", serverId);
                    errorResult.addProperty("error", "Bridge directory not ready");
                    errorResult.add("tools", new JsonArray());
                    return errorResult;
                }

                JsonObject stdinInput = new JsonObject();
                stdinInput.addProperty("serverId", serverId != null ? serverId : "");
                if (serverConfig != null) {
                    stdinInput.add("serverConfig", serverConfig);
                } else {
                    stdinInput.add("serverConfig", new JsonObject());
                }
                String stdinJson = gson.toJson(stdinInput);

                String scriptPath = new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath();
                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(scriptPath);
                command.add("codex");
                command.add("getMcpServerTools");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(bridgeDir);
                pb.redirectErrorStream(true);
                nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);
                pb.environment().put("CODEX_USE_STDIN", "true");

                injectCustomEnvVars(pb.environment(), CliConstants.CODEX_CATEGORY_MCP);

                process = pb.start();
                nodeService.getProcessManager().registerProcess(channelId, process);
                final Process finalProcess = process;

                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }

                java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicBoolean readerDone = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicReference<String> toolsJson = new java.util.concurrent.atomic.AtomicReference<>(null);
                final StringBuilder output = new StringBuilder();

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!found.get() && (line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                            if (line.startsWith("[MCP_SERVER_TOOLS]")) {
                                toolsJson.set(line.substring("[MCP_SERVER_TOOLS]".length()).trim());
                                found.set(true);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("[CodexMcpTools] Reader thread exception: " + e.getMessage());
                    } finally {
                        readerDone.set(true);
                    }
                });
                readerThread.start();

                long deadline = System.currentTimeMillis() + MCP_TOOLS_TIMEOUT_MS;
                while (!found.get() && !readerDone.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (process.isAlive()) {
                    PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                }

                String capturedTools = toolsJson.get();
                if (found.get() && capturedTools != null && !capturedTools.isEmpty()) {
                    try {
                        JsonObject result = gson.fromJson(capturedTools, JsonObject.class);
                        LOG.info("[CodexMcpTools] Got tools for " + serverId + " in " + elapsed + "ms");
                        return result;
                    } catch (Exception e) {
                        LOG.warn("[CodexMcpTools] Failed to parse MCP tools JSON: " + e.getMessage());
                    }
                }

                String outputStr = output.toString().trim();
                String jsonStr = extractLastJsonLine(outputStr);
                if (jsonStr != null) {
                    try {
                        JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                        if (jsonResult != null && jsonResult.has("success")) {
                            return jsonResult;
                        }
                    } catch (Exception e) {
                        LOG.debug("[CodexMcpTools] Fallback JSON parse failed: " + e.getMessage());
                    }
                }

                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("serverId", serverId);
                errorResult.addProperty("error", "Failed to get tools list");
                errorResult.add("tools", new JsonArray());
                return errorResult;
            } catch (Exception e) {
                LOG.error("[CodexMcpTools] Exception: " + e.getMessage(), e);
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("serverId", serverId);
                errorResult.addProperty("error", e.getMessage());
                errorResult.add("tools", new JsonArray());
                return errorResult;
            } finally {
                if (process != null) {
                    try {
                        if (process.isAlive()) {
                            PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                        }
                    } finally {
                        nodeService.getProcessManager().unregisterProcess(channelId, process);
                    }
                }
            }
        });
    }

    private String extractLastJsonLine(String outputStr) {
        if (outputStr == null || outputStr.isEmpty()) {
            return null;
        }
        String[] lines = outputStr.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        return null;
    }

    private void injectCustomEnvVars(Map<String, String> env, String category) {
        JsonObject activeProvider;
        try {
            activeProvider = settingsService.getActiveCodexProvider();
        } catch (Exception e) {
            LOG.error("[Codex] Failed to load active provider for env var injection (category=" + category + ")", e);
            return;
        }

        if (activeProvider == null) {
            return;
        }

        String field = CliConstants.CODEX_CATEGORY_MESSAGE.equals(category)
                ? CliConstants.CODEX_FIELD_MESSAGE_ENV_VARS
                : CliConstants.CODEX_FIELD_MCP_ENV_VARS;
        if (!activeProvider.has(field) || !activeProvider.get(field).isJsonArray()) {
            return;
        }

        JsonArray envVars = activeProvider.getAsJsonArray(field);
        for (JsonElement el : envVars) {
            if (!el.isJsonObject()) { continue; }
            JsonObject entry = el.getAsJsonObject();
            if (!entry.has("key") || !entry.has("value")) { continue; }

            try {
                String key = entry.get("key").getAsString().trim();
                String value = entry.get("value").getAsString();

                if (key.isEmpty()) { continue; }
                if (PROTECTED_ENV_KEYS.contains(key.toUpperCase())) {
                    LOG.warn("[Codex] Skipping protected env var: " + key);
                    continue;
                }
                if (value.length() > MAX_ENV_VAR_VALUE_LENGTH) {
                    LOG.warn("[Codex] Skipping env var '" + key + "': value exceeds " +
                            MAX_ENV_VAR_VALUE_LENGTH + " bytes");
                    continue;
                }
                env.put(key, value);
                LOG.debug("[Codex] Injected custom env var: " + key);
            } catch (Exception e) {
                LOG.warn("[Codex] Failed to inject single env var entry: " + e.getMessage());
            }
        }
    }
}
