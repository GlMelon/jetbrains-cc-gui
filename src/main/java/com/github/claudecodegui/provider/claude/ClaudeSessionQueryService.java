package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.AttachmentStorageService;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.UserMessageSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads persisted Claude session history via the Node bridge.
 */
class ClaudeSessionQueryService {

    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final int PROCESS_TIMEOUT_SECONDS = 30;
    /** stdout 总字节上限(32 MiB)。超阈即丢弃、terminate、抛异常,不保留半条消息。
     *  取值大于 NodeJsServiceCaller 的 1 MiB:getSession 的大会话历史 payload 合法可达
     *  数 MB(见 runSessionQuery 中的截断告警注释),1 MiB 会误杀正常大会话。 */
    private static final int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;
    /** 读线程 join 上限(秒):进程结束后等待读线程读完管道尾部的宽限。 */
    private static final long READER_JOIN_SECONDS = 5;
    private static final Pattern VALID_SESSION_ID = Pattern.compile("[a-zA-Z0-9_\\-]+");
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:\\[Image #\\d+:\\s*)?((?:[a-z]:[/\\\\]|/).+?\\.(?:png|jpe?g|gif|webp|bmp|svg))(?:\\])?\\s*$"
    );
    private static final String IMAGE_ATTACHMENT_HINT =
            "The user has attached the image(s) above. Please use the Read tool to view them.";
    private static final String IMAGE_ATTACHMENT_CONTENT_HINT =
            "The user attached the image file(s) above. Use the image content to answer the request.";
    private static final Pattern CLI_IMAGE_READ_INSTRUCTION_PATTERN = Pattern.compile(
            "(?im)^\\s*Use the Read tool to inspect this image file, then answer using its visible content:\\s*"
                    + "(?:[a-z]:[/\\\\]|/).+?\\.(?:png|jpe?g|gif|webp|bmp|svg)\\s*$");

    private final Logger log;
    private final Gson gson;
    private final NodeDetector nodeDetector;
    private final Supplier<File> sdkDirSupplier;
    private final ProcessManager processManager;
    private final EnvironmentConfigurator envConfigurator;
    private final ClaudeJsonOutputExtractor outputExtractor;

    ClaudeSessionQueryService(
            Logger log,
            Gson gson,
            NodeDetector nodeDetector,
            Supplier<File> sdkDirSupplier,
            ProcessManager processManager,
            EnvironmentConfigurator envConfigurator,
            ClaudeJsonOutputExtractor outputExtractor
    ) {
        this.log = log;
        this.gson = gson;
        this.nodeDetector = nodeDetector;
        this.sdkDirSupplier = sdkDirSupplier;
        this.processManager = processManager;
        this.envConfigurator = envConfigurator;
        this.outputExtractor = outputExtractor;
    }

    List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        try {
            JsonObject jsonResult = runSessionQuery("getSession", sessionId, cwd, "getSessionMessages");

            if (jsonResult.has("success") && jsonResult.get("success").getAsBoolean()) {
                if (jsonResult.has("messages")) {
                    JsonArray messagesArray = jsonResult.getAsJsonArray("messages");
                    return normalizeClaudeHistoryMessages(messagesArray);
                }
                return new ArrayList<>();
            }

            String errorMsg = (jsonResult.has("error") && !jsonResult.get("error").isJsonNull())
                    ? jsonResult.get("error").getAsString()
                    : "Unknown error";
            throw new RuntimeException("Get session failed: " + errorMsg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get session messages: " + e.getMessage(), e);
        }
    }

    /**
     * Converts raw Claude JSONL rows into transcript messages and annotates user
     * messages from the authoritative file-history checkpoints. Snapshot rows may
     * appear before or after their matching user row, so collection and annotation
     * intentionally happen in two passes.
     */
    static List<JsonObject> normalizeClaudeHistoryMessages(JsonArray messagesArray) {
        List<JsonObject> messages = new ArrayList<>();
        if (messagesArray == null || messagesArray.size() == 0) {
            return messages;
        }

        Set<String> checkpointMessageIds = new HashSet<>();
        for (JsonElement element : messagesArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject rawMessage = element.getAsJsonObject();
            if (!hasStringProperty(rawMessage, CommonConstants.JSON_KEY_TYPE,
                    CommonConstants.MSG_TYPE_FILE_HISTORY_SNAPSHOT)) {
                continue;
            }

            String messageId = getStringProperty(rawMessage, CommonConstants.JSON_KEY_MESSAGE_ID);
            if (messageId == null
                    && rawMessage.has(CommonConstants.JSON_KEY_SNAPSHOT)
                    && rawMessage.get(CommonConstants.JSON_KEY_SNAPSHOT).isJsonObject()) {
                messageId = getStringProperty(
                        rawMessage.getAsJsonObject(CommonConstants.JSON_KEY_SNAPSHOT),
                        CommonConstants.JSON_KEY_MESSAGE_ID);
            }
            if (messageId != null && !messageId.isBlank()) {
                checkpointMessageIds.add(messageId);
            }
        }

        for (JsonElement element : messagesArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject rawMessage = element.getAsJsonObject();
            if (hasStringProperty(rawMessage, CommonConstants.JSON_KEY_TYPE,
                    CommonConstants.MSG_TYPE_FILE_HISTORY_SNAPSHOT)) {
                continue;
            }

            JsonObject annotatedMessage = rawMessage;
            if (hasStringProperty(rawMessage, CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_USER)) {
                annotatedMessage = rawMessage.deepCopy();
                String userMessageId = getStringProperty(rawMessage, CommonConstants.JSON_KEY_UUID);
                annotatedMessage.addProperty(
                        CommonConstants.JSON_KEY_REWINDABLE,
                        userMessageId != null && checkpointMessageIds.contains(userMessageId));
            }
            messages.add(normalizeClaudeHistoryMessage(annotatedMessage));
        }
        return messages;
    }

    private static boolean hasStringProperty(JsonObject object, String propertyName, String expectedValue) {
        String value = getStringProperty(object, propertyName);
        return expectedValue.equals(value);
    }

    private static String getStringProperty(JsonObject object, String propertyName) {
        if (object == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement value = object.get(propertyName);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    JsonObject getLatestUserMessage(String sessionId, String cwd) {
        try {
            JsonObject jsonResult = runSessionQuery("getLatestUserMessage", sessionId, cwd, "getLatestUserMessage");

            if (jsonResult.has("success") && jsonResult.get("success").getAsBoolean()) {
                if (jsonResult.has("message") && jsonResult.get("message").isJsonObject()) {
                    JsonObject latestUserMessage = normalizeClaudeHistoryMessage(
                            jsonResult.getAsJsonObject("message"));
                    // CLI-mode turns never reload history, so rewindable availability must ride
                    // along with the uuid sync instead of the history-load annotation pass.
                    if (latestUserMessage != null
                            && jsonResult.has("messageHasCheckpoint")
                            && !jsonResult.get("messageHasCheckpoint").isJsonNull()) {
                        latestUserMessage.addProperty(
                                CommonConstants.JSON_KEY_REWINDABLE,
                                jsonResult.get("messageHasCheckpoint").getAsBoolean());
                    }
                    return latestUserMessage;
                }
                return null;
            }

            String errorMsg = (jsonResult.has("error") && !jsonResult.get("error").isJsonNull())
                    ? jsonResult.get("error").getAsString()
                    : "Unknown error";
            throw new RuntimeException("Get latest user message failed: " + errorMsg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get latest user message: " + e.getMessage(), e);
        }
    }

    private JsonObject runSessionQuery(String commandName, String sessionId, String cwd, String logPrefix) throws Exception {
        if (sessionId == null || !VALID_SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
        }

        String node = nodeDetector.findNodeExecutable();

        File workDir = sdkDirSupplier.get();
        if (workDir == null || !workDir.exists()) {
            throw new RuntimeException("Bridge directory not ready or invalid");
        }

        List<String> command = NodeDetector.buildNodeScriptCommand(
                node, new File(workDir, CHANNEL_SCRIPT).getAbsolutePath());
        command.add(CommonConstants.PROVIDER_CLAUDE);
        command.add(commandName);
        command.add(sessionId);
        // Only translate the cwd to a WSL path when the active node is a WSL binary,
        // mirroring the original node cwd normalization; a native node keeps the cwd as-is.
        String cwdArg = "";
        if (cwd != null) {
            cwdArg = NodeDetector.isWslPath(node) ? NodeDetector.convertToWslPath(cwd) : cwd;
        }
        command.add(cwdArg);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        envConfigurator.updateProcessEnvironment(pb, node);

        // L5 fix: register with ProcessManager so cleanupAllProcesses sees this child.
        String channelId = ProcessManager.newChannelId("claude-session-query");
        Process process = null;
        Thread stdoutReader = null;
        InputStream stdoutStream = null;
        CapturedOutput output = new CapturedOutput();
        try {
            process = pb.start();
            processManager.registerProcess(channelId, process);

            // S3 同款加固(见 NodeJsServiceCaller.executeNodeScript):stdout 由独立守护
            // 线程 drain 到有界缓冲,主线程 waitFor 真超时 —— 原实现在主线程 readLine 到
            // EOF 之后才 waitFor,子进程挂起不关 stdout 时 readLine 永不返回,根本到不了
            // waitFor,调用线程永久阻塞,超时形同虚设。
            final Process proc = process;
            stdoutStream = process.getInputStream();
            final InputStream procStdout = stdoutStream;
            stdoutReader = new Thread(
                    () -> drainStdoutCapped(proc, procStdout, output),
                    "claude-session-query-reader");
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                joinQuietly(stdoutReader, READER_JOIN_SECONDS);
                throw new RuntimeException("Node.js process timed out after " + PROCESS_TIMEOUT_SECONDS + " seconds");
            }

            // 进程已结束:等读线程读完管道尾部,再判定 cap。
            joinQuietly(stdoutReader, READER_JOIN_SECONDS);
            if (output.overflow) {
                throw new RuntimeException("Node.js process output exceeded size cap (max "
                        + MAX_OUTPUT_BYTES + " bytes)");
            }
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
            closeQuietly(stdoutStream);
            joinQuietly(stdoutReader, READER_JOIN_SECONDS);
            if (process != null) {
                processManager.unregisterProcess(channelId, process);
            }
        }

        String outputStr = output.builder.toString().trim();
        log.debug("[" + logPrefix + "] Raw output length: " + outputStr.length());
        if (log.isDebugEnabled()) {
            log.debug("[" + logPrefix + "] Raw output (first 300 chars): "
                    + (outputStr.length() > 300 ? outputStr.substring(0, 300) + "..." : outputStr));
        }

        String jsonStr = outputExtractor.extractLastJsonLine(outputStr);
        if (jsonStr == null) {
            log.error("[" + logPrefix + "] Failed to extract JSON from output");
            throw new RuntimeException("Failed to extract JSON from Node.js output");
        }

        // A well-formed JSON object must end with '}'. If it doesn't, the Node child
        // exited before its stdout buffer fully drained (a known race for large
        // getSession payloads) and the JSON was truncated mid-stream. Log the lengths
        // so the failure is diagnosable instead of leaving only a cryptic Gson error.
        // extractLastJsonLine already trims its return value, so no trim is needed here
        // (avoiding an O(n) copy of the multi-megabyte payload on every getSession).
        if (!jsonStr.endsWith("}")) {
            log.warn("[" + logPrefix + "] Extracted JSON appears truncated: jsonLength="
                    + jsonStr.length() + ", rawOutputLength=" + outputStr.length());
        }

        if (log.isDebugEnabled()) {
            log.debug("[" + logPrefix + "] Extracted JSON: "
                    + (jsonStr.length() > 500 ? jsonStr.substring(0, 500) + "..." : jsonStr));
        }
        JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
        log.debug("[" + logPrefix + "] JSON parsed successfully, success="
                + (jsonResult.has("success") ? jsonResult.get("success").getAsBoolean() : "null"));
        return jsonResult;
    }

    /**
     * 分块 drain stdout 到有界缓冲(总字节 {@link #MAX_OUTPUT_BYTES}):超阈即置 overflow
     * 并 {@code destroyForcibly} 打断子进程(否则子进程会因管道写阻塞而永不退出,令主线程
     * 在 {@code waitFor} 干等 timeout),主线程抛异常、不保留半条消息。
     * <p>
     * {@code output} 由本(读)线程写、主线程在 join 后读(join 建立 happens-before,
     * 单写者→单读者,安全)。
     */
    private static void drainStdoutCapped(Process process, InputStream in, CapturedOutput output) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
                if (baos.size() > MAX_OUTPUT_BYTES) {
                    output.overflow = true;
                    try {
                        process.destroyForcibly();
                    } catch (Exception ignored) {
                        // 平台感知的进程树清理由主线程 finally 兜底,此处忽略 destroy 异常。
                    }
                    return;
                }
            }
            output.builder.append(baos.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 子进程被 terminate 导致流关闭 —— 正常退出路径,不抛。
        }
    }

    private static void joinQuietly(Thread t, long seconds) {
        if (t == null) {
            return;
        }
        try {
            t.join(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The process may have closed the stream already.
        }
    }

    /** stdout 捕获结果:原始输出 + 超阈标志。读线程写,主线程 join 后读。 */
    private static final class CapturedOutput {
        final StringBuilder builder = new StringBuilder();
        volatile boolean overflow = false;
    }

    static JsonObject normalizeClaudeHistoryMessage(JsonObject originalMessage) {
        if (originalMessage == null
                || !originalMessage.has("type")
                || !"user".equals(originalMessage.get("type").getAsString())
                || !originalMessage.has("message")
                || !originalMessage.get("message").isJsonObject()) {
            return originalMessage;
        }

        JsonObject message = originalMessage.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return originalMessage;
        }

        JsonElement contentElement = message.get("content");
        if (contentElement.isJsonPrimitive() && contentElement.getAsJsonPrimitive().isString()) {
            ClaudeImageReferenceRewrite rewrite = rewriteClaudeImageReferenceText(contentElement.getAsString(), false);
            if (!rewrite.changed) {
                return originalMessage;
            }

            JsonObject normalizedMessage = originalMessage.deepCopy();
            normalizedMessage.getAsJsonObject("message").add("content", rewrite.contentBlocks);
            return normalizedMessage;
        }

        if (!contentElement.isJsonArray()) {
            return originalMessage;
        }

        JsonArray originalBlocks = contentElement.getAsJsonArray();
        // When the persisted content already carries image blocks (typical for
        // SDK-mode writes where buildContentBlocks emits both an image block and a
        // "[Image #N: path]" text reference), suppress creating duplicate image
        // blocks from those text markers. Otherwise the same image renders twice.
        boolean alreadyHasImageBlock = containsImageBlock(originalBlocks);
        JsonArray rebuiltBlocks = new JsonArray();
        boolean changed = false;

        for (JsonElement blockElement : originalBlocks) {
            if (!blockElement.isJsonObject()) {
                rebuiltBlocks.add(blockElement.deepCopy());
                continue;
            }

            JsonObject block = blockElement.getAsJsonObject();
            if (!isTextBlock(block)) {
                rebuiltBlocks.add(block.deepCopy());
                continue;
            }

            ClaudeImageReferenceRewrite rewrite = rewriteClaudeImageReferenceText(
                    block.get("text").getAsString(), alreadyHasImageBlock);
            if (!rewrite.changed) {
                rebuiltBlocks.add(block.deepCopy());
                continue;
            }

            changed = true;
            for (JsonElement normalizedBlock : rewrite.contentBlocks) {
                rebuiltBlocks.add(normalizedBlock);
            }
        }

        if (!changed) {
            return originalMessage;
        }

        JsonObject normalizedMessage = originalMessage.deepCopy();
        normalizedMessage.getAsJsonObject("message").add("content", rebuiltBlocks);
        return normalizedMessage;
    }

    private static boolean containsImageBlock(JsonArray blocks) {
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (block.has("type") && "image".equals(block.get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextBlock(JsonObject block) {
        return block.has("type")
                && "text".equals(block.get("type").getAsString())
                && block.has("text")
                && !block.get("text").isJsonNull();
    }

    private static ClaudeImageReferenceRewrite rewriteClaudeImageReferenceText(String text, boolean suppressImageBlockCreation) {
        if (text == null) {
            return ClaudeImageReferenceRewrite.unchanged(null);
        }

        Matcher matcher = IMAGE_REFERENCE_PATTERN.matcher(text);
        StringBuilder remainingText = new StringBuilder();
        JsonArray contentBlocks = new JsonArray();
        int lastEnd = 0;
        boolean sawReference = false;
        boolean restoredImage = false;
        boolean strippedReference = false;

        while (matcher.find()) {
            remainingText.append(text, lastEnd, matcher.start());
            lastEnd = matcher.end();
            sawReference = true;

            if (suppressImageBlockCreation) {
                // The persisted message already carries an image block; only erase
                // the inline "[Image #N: path]" marker so the text reads cleanly.
                strippedReference = true;
                continue;
            }

            String imagePath = matcher.group(1) != null ? matcher.group(1).trim() : "";
            JsonObject imageBlock = createLocalImageBlock(imagePath);
            if (imageBlock != null) {
                contentBlocks.add(imageBlock);
                restoredImage = true;
            } else {
                remainingText.append(matcher.group());
            }
        }

        if (!sawReference || (!restoredImage && !strippedReference)) {
            String sanitized = normalizeRemainingText(text);
            if (sanitized.equals(text)) {
                return ClaudeImageReferenceRewrite.unchanged(text);
            }
            appendTextBlock(contentBlocks, sanitized);
            return new ClaudeImageReferenceRewrite(true, contentBlocks);
        }

        remainingText.append(text.substring(lastEnd));
        String cleanedText = normalizeRemainingText(remainingText.toString());
        appendTextBlock(contentBlocks, cleanedText);
        return new ClaudeImageReferenceRewrite(true, contentBlocks);
    }

    private static String normalizeRemainingText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n");
        normalized = normalized.replace("\r", "\n");
        normalized = normalized.replace(IMAGE_ATTACHMENT_HINT, "");
        normalized = normalized.replace(IMAGE_ATTACHMENT_CONTENT_HINT, "");
        normalized = CLI_IMAGE_READ_INSTRUCTION_PATTERN.matcher(normalized).replaceAll("");
        normalized = UserMessageSanitizer.sanitizeUserFacingText(normalized);
        normalized = normalized.replaceAll("(?m)^[ \\t]+$", "");
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        normalized = normalized.replaceAll("^(?:\\s*\\n)+", "");
        normalized = normalized.replaceAll("(?:\\n\\s*)+$", "");
        return normalized.trim();
    }

    private static void appendTextBlock(JsonArray contentBlocks, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        contentBlocks.add(textBlock);
    }

    private static JsonObject createLocalImageBlock(String imagePath) {
        return AttachmentStorageService.getInstance().createImageBlockFromPath(imagePath);
    }

    private static final class ClaudeImageReferenceRewrite {
        private final boolean changed;
        private final JsonArray contentBlocks;

        private ClaudeImageReferenceRewrite(boolean changed, JsonArray contentBlocks) {
            this.changed = changed;
            this.contentBlocks = contentBlocks;
        }

        private static ClaudeImageReferenceRewrite unchanged(String originalText) {
            JsonArray contentBlocks = new JsonArray();
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", originalText != null ? originalText : "");
            contentBlocks.add(textBlock);
            return new ClaudeImageReferenceRewrite(false, contentBlocks);
        }
    }
}
