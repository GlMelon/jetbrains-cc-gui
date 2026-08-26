package com.github.claudecodegui.handler.dsh;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.dsh.DshEnvSupport;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DSH host 生命周期 spawn 工具:运行 {@code node channel-manager.js dsh <command>} one-shot 子进程,
 * 返回 JSON 状态 payload。复刻 upstream DshHostHandler.runDshCommand,适配本地 NodeService API。
 * <p>
 * 供 4 个 typed ActionHandler(GetDshStatus/StartDshHost/StopDshHost/SaveDshSettings)共享,
 * 各 ActionHandler 异步调用并经 {@code window.updateDshStatus} 下行结果。
 */
final class DshHostRunner {

    private static final Logger LOG = Logger.getInstance(DshHostRunner.class);
    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final long STATUS_TIMEOUT_SECONDS = 30L;
    private static final long LIFECYCLE_TIMEOUT_SECONDS = 60L;
    private static final int MAX_OUTPUT_CHARS = 64_000;
    private static final int MAX_STDERR_CHARS = 8_192;

    private DshHostRunner() {
    }

    static JsonObject getStatus() {
        return runDshCommand("status", null, STATUS_TIMEOUT_SECONDS);
    }

    static JsonObject startHost() {
        return runDshCommand("ensureHost", null, LIFECYCLE_TIMEOUT_SECONDS);
    }

    static JsonObject stopHost() {
        return runDshCommand("stopHost", null, STATUS_TIMEOUT_SECONDS);
    }

    /** 解析+校验+持久化 dsh 设置,完成后返回最新 status。 */
    static JsonObject saveSettings(String content) {
        try {
            if (content == null || content.isBlank()) {
                return errorPayload("Empty DSH settings payload");
            }
            JsonObject payload = JsonParser.parseString(content).getAsJsonObject();
            CodemossSettingsService s = CodemossSettingsService.getInstance();
            // 先全部校验,再一次性持久化(避免半写)
            String bin = null;
            String host = null;
            int port = -1;
            boolean hasPort = false;
            boolean autoStart = false;
            boolean hasAutoStart = false;
            if (payload.has("bin")) {
                bin = asTrimmedString(payload.get("bin"));
                String e = validateDshBin(bin);
                if (e != null) {
                    return errorPayload(e);
                }
            }
            if (payload.has("host")) {
                host = asTrimmedString(payload.get("host"));
                String e = validateDshHost(host);
                if (e != null) {
                    return errorPayload(e);
                }
            }
            if (payload.has("port")) {
                port = payload.get("port").isJsonPrimitive() ? payload.get("port").getAsInt() : -1;
                hasPort = true;
            }
            if (payload.has("autoStart")) {
                autoStart = payload.get("autoStart").isJsonPrimitive() && payload.get("autoStart").getAsBoolean();
                hasAutoStart = true;
            }
            if (bin != null) {
                s.setDshBin(bin);
            }
            if (host != null) {
                s.setDshHost(host);
            }
            if (hasPort) {
                s.setDshPort(port);
            }
            if (hasAutoStart) {
                s.setDshAutoStart(autoStart);
            }
            return getStatus();
        } catch (Exception e) {
            LOG.warn("[DshHost] saveSettings failed: " + e.getMessage());
            return errorPayload("Invalid DSH settings: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String asTrimmedString(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        return el.getAsString().trim();
    }

    static JsonObject errorPayload(String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("error", message == null ? "DSH host error" : message);
        return payload;
    }

    static JsonObject runDshCommand(String command, JsonObject stdinPayload, long timeoutSeconds) {
        Process process = null;
        try {
            NodeService nodeService = NodeService.getInstance();
            String node = nodeService.getNodeDetector().findNodeExecutable();
            if (node == null) {
                return errorPayload("Node.js not found");
            }
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null || !bridgeDir.isDirectory()) {
                return errorPayload("Bridge directory not ready");
            }
            File script = new File(bridgeDir, CHANNEL_SCRIPT);
            if (!script.isFile()) {
                return errorPayload(CHANNEL_SCRIPT + " not found");
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(node);
            cmd.add(script.getAbsolutePath());
            cmd.add(CommonConstants.PROVIDER_DSH);
            cmd.add(command);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(false);
            Map<String, String> env = pb.environment();
            nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);
            DshEnvSupport.inject(env, CodemossSettingsService.getInstance());
            if (stdinPayload != null) {
                env.put("DSH_USE_STDIN", "true");
            }

            process = pb.start();
            writeStdin(process, stdinPayload);

            StringBuilder output = new StringBuilder();
            StringBuilder stderrTail = new StringBuilder();
            Thread stdoutReader = drainStream(process.getInputStream(), output, MAX_OUTPUT_CHARS);
            Thread stderrDrainer = drainStream(process.getErrorStream(), stderrTail, MAX_STDERR_CHARS);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("[DshHost] dsh " + command + " timed out");
                return errorPayload("Timed out running dsh " + command);
            }
            stdoutReader.join(2000L);
            stderrDrainer.join(2000L);

            JsonObject payload = extractJsonObject(output.toString());
            if (payload == null) {
                LOG.warn("[DshHost] no JSON output from dsh " + command);
                return errorPayload("No JSON output from dsh " + command);
            }
            return payload;
        } catch (Exception e) {
            LOG.warn("[DshHost] " + command + " failed: " + e.getMessage());
            return errorPayload(e.getMessage() != null ? e.getMessage() : "dsh " + command + " failed");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void writeStdin(Process process, JsonObject stdinPayload) {
        if (stdinPayload == null) {
            return;
        }
        byte[] data = GsonHolder.GSON.toJson(stdinPayload).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = process.getOutputStream()) {
            os.write(data);
            os.flush();
        } catch (Exception ignored) {
            // 进程可能已退出,忽略
        }
    }

    private static Thread drainStream(InputStream in, StringBuilder sink, int maxChars) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink.length() < maxChars) {
                        if (sink.length() + line.length() + 1 <= maxChars) {
                            sink.append(line).append('\n');
                        } else {
                            int remaining = maxChars - sink.length();
                            if (remaining > 0) {
                                sink.append(line, 0, Math.min(remaining, line.length())).append('\n');
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // drain 不应中断主流程
            }
        }, "dsh-host-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static JsonObject extractJsonObject(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(trimmed).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    static String validateDshBin(String bin) {
        if (bin == null || bin.isEmpty()) {
            return null;
        }
        for (int i = 0; i < bin.length(); i++) {
            if (Character.isISOControl(bin.charAt(i))) {
                return "Invalid DSH bin path (contains control characters)";
            }
        }
        File candidate = new File(bin);
        if (candidate.exists() && !candidate.isFile()) {
            return "Invalid DSH bin path (not a regular file): " + bin;
        }
        return null;
    }

    static String validateDshHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == ':') {
                return "Invalid DSH host (host name or IP only, no scheme or port): " + host;
            }
        }
        return null;
    }
}
