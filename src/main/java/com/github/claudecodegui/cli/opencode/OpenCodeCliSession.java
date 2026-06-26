package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.*;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCode CLI 会话：每个 Tab 独立实例，使用 opencode api 命令与 OpenCode HTTP API 交互。
 * 通过 session ID 实现多轮连续对话。
 * 完全不依赖 SDK / ai-bridge。
 */
public class OpenCodeCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(OpenCodeCliSession.class);
    private static final Charset WINDOWS_CHINESE_CHARSET = Charset.forName("GBK");

    private final String tabId;
    private final Gson gson = GsonHolder.GSON;

    // 当前 session ID（从 session.create 响应获取）
    private volatile String sessionId;
    // 当前活跃进程（用于中断）
    private volatile Process activeProcess;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);

    public OpenCodeCliSession(String tabId) {
        this.tabId = tabId;
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        return CliSessionExecutor.runAsync(() -> {
            StringBuilder diagnostic = new StringBuilder();
            StringBuilder cliError = new StringBuilder();
            Process process = null;
            try {
                // 确保有 session ID
                if (sessionId == null) {
                    sessionId = createSession(request, diagnostic);
                    if (sessionId == null) {
                        String err = "Failed to create OpenCode session";
                        callback.onError(err);
                        callback.onComplete(false, null, err);
                        return;
                    }
                    callback.onMessage(CliConstants.MSG_SESSION_ID, sessionId);
                }

                List<String> cmd = buildPromptCommand(request);
                LOG.info("[OpenCodeCliSession][" + tabId + "] Command: " + String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Map<String, String> cliEnv = pb.environment();
                cliEnv.clear();
                cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
                cliEnv.put(CliConstants.ARG_NO_COLOR, "1");
                CliEnvironmentBuilder.configureProjectPath(cliEnv, request.cwd());
                if (!request.extraEnv().isEmpty()) {
                    cliEnv.putAll(request.extraEnv());
                }

                if (request.cwd() != null && !request.cwd().isBlank()) {
                    File cwd = new File(request.cwd());
                    if (cwd.isDirectory()) {
                        pb.directory(cwd);
                    }
                }

                process = pb.start();
                activeProcess = process;

                // 写入 prompt JSON 到 stdin
                byte[] promptInput = buildPromptInput(request);
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(promptInput);
                    stdin.flush();
                }

                StringBuilder assistantContent = new StringBuilder();
                try (InputStream rawIn = process.getInputStream()) {
                    ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
                    byte[] readBuf = new byte[8192];
                    int n;
                    while ((n = rawIn.read(readBuf)) != -1) {
                        for (int i = 0; i < n; i++) {
                            byte b = readBuf[i];
                            if (b == '\n') {
                                processLine(lineBuf, callback, assistantContent, cliError);
                            } else {
                                lineBuf.write(b);
                            }
                        }
                    }
                    if (lineBuf.size() > 0) {
                        processLine(lineBuf, callback, assistantContent, cliError);
                    }
                }

                if (!process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                int exitCode = process.exitValue();

                if (userInterrupted.get()) {
                    callback.onInterrupted(assistantContent.toString(), CliConstants.I18N_REQUEST_INTERRUPTED);
                } else if (exitCode == 0) {
                    if (!cliError.isEmpty()) {
                        String err = CliErrorFormatter.formatError("OpenCode", cliError.toString());
                        callback.onError(err);
                        callback.onComplete(false, assistantContent.toString(), err);
                    } else {
                        callback.onMessage(CliConstants.MSG_STREAM_END, "");
                        callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                        callback.onComplete(true, assistantContent.toString(), null);
                    }
                } else if (!userInterrupted.get()) {
                    String err = CliErrorFormatter.formatExitError("OpenCode", exitCode, diagnostic);
                    callback.onError(err);
                    callback.onComplete(false, assistantContent.toString(), err);
                }
            } catch (Exception e) {
                LOG.warn("[OpenCodeCliSession][" + tabId + "] send failed", e);
                if (userInterrupted.get()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError("OpenCode", e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                }
            } finally {
                activeProcess = null;
                userInterrupted.set(false);
            }
        });
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        Process p = activeProcess;
        if (p != null) {
            long startNanos = System.nanoTime();
            p.destroyForcibly();
            LOG.info("[TabPerf] OpenCodeCliSession.interrupt returned in "
                    + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
        }
    }

    @Override
    public void dispose() {
        interrupt();
    }

    // ── session management ─────────────────────────────────────────────────

    private String createSession(CliSendRequest request, StringBuilder diagnostic) {
        try {
            String executable = OpenCodeCliResolver.findExecutable();
            List<String> cmd = List.of(
                    executable,
                    CliConstants.OPENCODE_ARG_API,
                    CliConstants.OPENCODE_API_SESSION_CREATE
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> cliEnv = pb.environment();
            cliEnv.clear();
            cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
            if (request.cwd() != null && !request.cwd().isBlank()) {
                File cwd = new File(request.cwd());
                if (cwd.isDirectory()) {
                    pb.directory(cwd);
                }
            }

            Process proc = pb.start();
            String output = readAllOutput(proc);
            proc.waitFor(10, TimeUnit.SECONDS);

            if (proc.exitValue() == 0 && output != null) {
                // Try to parse session ID from JSON response
                // OpenCode returns session object with id field
                JsonObject resp = parseJsonOutput(output);
                if (resp != null && resp.has("id")) {
                    return resp.get("id").getAsString();
                }
                // Try nested data.id
                if (resp != null && resp.has("data") && resp.get("data").isJsonObject()) {
                    JsonObject data = resp.getAsJsonObject("data");
                    if (data.has("id")) {
                        return data.get("id").getAsString();
                    }
                }
            }
            if (diagnostic != null) {
                diagnostic.append(output);
            }
        } catch (Exception e) {
            LOG.warn("[OpenCodeCliSession] createSession failed", e);
            if (diagnostic != null) {
                diagnostic.append(e.getMessage());
            }
        }
        return null;
    }

    // ── command builder ──────────────────────────────────────────────────────

    private List<String> buildPromptCommand(CliSendRequest request) {
        String executable = OpenCodeCliResolver.findExecutable();
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add(CliConstants.OPENCODE_ARG_API);
        // Use operation ID for prompt
        cmd.add("v2.session.prompt");
        cmd.add(CliConstants.OPENCODE_ARG_DATA);
        cmd.add(buildPromptJson(request));
        return cmd;
    }

    private String buildPromptJson(CliSendRequest request) {
        JsonObject body = new JsonObject();
        JsonArray parts = new JsonArray();

        // Add text part
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", buildPromptText(request));
        parts.add(textPart);

        body.add("parts", parts);
        return gson.toJson(body);
    }

    private String buildPromptText(CliSendRequest request) {
        StringBuilder sb = new StringBuilder(request.message());
        if (request.openedFiles() != null && request.openedFiles().size() > 0) {
            sb.append(CliConstants.PROMPT_OPENED_FILES).append(gson.toJson(request.openedFiles()));
        }
        if (!request.fileTagPaths().isEmpty()) {
            sb.append(CliConstants.PROMPT_REFERENCED);
            for (String p : request.fileTagPaths()) {
                sb.append("- ").append(p).append('\n');
            }
        }
        if (request.agentPrompt() != null && !request.agentPrompt().isBlank()) {
            sb.append(CliConstants.PROMPT_AGENT_ROLE).append(request.agentPrompt());
        }
        return sb.toString();
    }

    private byte[] buildPromptInput(CliSendRequest request) {
        return buildPromptJson(request).getBytes(StandardCharsets.UTF_8);
    }

    // ── output parsing ───────────────────────────────────────────────────────

    private void processLine(
            ByteArrayOutputStream lineBuf,
            CliSessionCallback callback,
            StringBuilder assistantContent,
            StringBuilder cliError
    ) {
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return;
        }

        String line = decodeLine(bytes, len);
        if (!line.isBlank()) {
            parseEvent(line, callback, assistantContent, cliError);
        }
    }

    private void parseEvent(
            String line,
            CliSessionCallback callback,
            StringBuilder assistantContent,
            StringBuilder cliError
    ) {
        try {
            if (line.trim().isEmpty()) {
                return;
            }
            JsonObject event = gson.fromJson(line, JsonObject.class);
            if (event == null) {
                return;
            }

            // Handle OpenCode SSE-style events or JSON responses
            String type = getString(event, "type");
            if (type != null) {
                switch (type) {
                    case "session.created", "session.started" -> {
                        callback.onMessage(CliConstants.MSG_STREAM_START, "");
                        callback.onMessage(CliConstants.MSG_MESSAGE_START, "");
                    }
                    case "message.created", "message.updated" -> {
                        // Extract content from message events
                        if (event.has("message") && event.get("message").isJsonObject()) {
                            JsonObject msg = event.getAsJsonObject("message");
                            String role = getString(msg, "role");
                            if ("assistant".equals(role)) {
                                extractMessageContent(msg, callback, assistantContent);
                            }
                        }
                    }
                    case "session.completed", "session.done" -> {
                        callback.onMessage(CliConstants.MSG_STREAM_END, "");
                        callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                    }
                    case "error" -> {
                        String msg = getString(event, "message");
                        if (msg == null) msg = event.toString();
                        if (cliError != null) {
                            CliErrorFormatter.appendDiagnosticLine(cliError, msg);
                        }
                    }
                    default -> {
                        // Ignore unknown event types
                    }
                }
            } else {
                // No type field - treat as content delta
                String text = line + "\n";
                assistantContent.append(text);
                callback.onMessage(CliConstants.MSG_CONTENT_DELTA, text);
            }
        } catch (Exception e) {
            // Non-JSON line - treat as content delta
            if (!line.trim().isEmpty()) {
                String text = line + "\n";
                assistantContent.append(text);
                callback.onMessage(CliConstants.MSG_CONTENT_DELTA, text);
            }
        }
    }

    private void extractMessageContent(
            JsonObject msg,
            CliSessionCallback callback,
            StringBuilder assistantContent
    ) {
        if (!msg.has("content") || !msg.get("content").isJsonArray()) {
            return;
        }
        JsonArray content = msg.getAsJsonArray("content");
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String blockType = getString(block, "type");
            if ("text".equals(blockType)) {
                String text = getString(block, "text");
                if (text != null && !text.isEmpty()) {
                    String delta = appendedDelta(assistantContent.toString(), text);
                    if (!delta.isEmpty()) {
                        assistantContent.append(delta);
                        callback.onMessage(CliConstants.MSG_CONTENT_DELTA, delta);
                    }
                }
            }
        }
    }

    // ── utility methods ──────────────────────────────────────────────────────

    private static String decodeLine(byte[] bytes, int len) {
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = utf8Decoder.decode(ByteBuffer.wrap(bytes, 0, len));
            return cb.toString();
        } catch (CharacterCodingException e) {
            Charset fallback = Charset.defaultCharset();
            if (WINDOWS_CHINESE_CHARSET.equals(fallback)) {
                return new String(bytes, 0, len, fallback);
            }
            String decoded = new String(bytes, 0, len, WINDOWS_CHINESE_CHARSET);
            if (!decoded.contains("�")) {
                return decoded;
            }
            return new String(bytes, 0, len, fallback);
        }
    }

    private static String readAllOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject parseJsonOutput(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            // Try to find JSON object in output
            String trimmed = output.trim();
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return gson.fromJson(trimmed.substring(start, end + 1), JsonObject.class);
            }
        } catch (Exception e) {
            // Not valid JSON
        }
        return null;
    }

    private static String appendedDelta(String previous, String next) {
        String oldText = previous != null ? previous : "";
        String newText = next != null ? next : "";
        if (newText.isEmpty() || newText.equals(oldText)) {
            return "";
        }
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }
}
