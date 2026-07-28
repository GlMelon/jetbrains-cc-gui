package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.protocol.payload.HistoryExportPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Service for exporting bounded session data through the typed downstream bus. */
class HistoryExportService {

    private static final Logger LOG = Logger.getInstance(HistoryExportService.class);

    private final HandlerContext context;
    private final HistoryProviderRegistry historyProviderRegistry;
    private final HistoryExportPayloadBuilder payloadBuilder;

    HistoryExportService(HandlerContext context, HistoryProviderRegistry historyProviderRegistry) {
        this(context, historyProviderRegistry, new HistoryExportPayloadBuilder());
    }

    HistoryExportService(
            HandlerContext context,
            HistoryProviderRegistry historyProviderRegistry,
            HistoryExportPayloadBuilder payloadBuilder
    ) {
        this.context = context;
        this.historyProviderRegistry = historyProviderRegistry;
        this.payloadBuilder = payloadBuilder;
    }

    void handleExportSession(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> exportSession(content, currentProvider));
    }

    /**
     * Opens a printable, sanitized HTML transcript in the system browser so the user can
     * "Save as PDF" via the browser's native print engine. Reuses the bounded HTML renderer
     * (same budget + sanitizer as the HTML download) — no PDF library, no binary transport.
     */
    void handlePrintSessionPdf(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> printSessionPdf(content, currentProvider));
    }

    private void exportSession(String content, String currentProvider) {
        LOG.info("[HistoryHandler] ========== 开始导出会话 ==========");
        try {
            JsonObject exportRequest = parseRequest(content);
            String sessionId = requiredString(exportRequest, HistoryExportPayloadField.SESSION_ID);
            String title = optionalString(exportRequest, HistoryExportPayloadField.TITLE);
            String formatValue = optionalString(exportRequest, HistoryExportPayloadField.FORMAT);
            HistoryExportFormat format = formatValue.isBlank()
                    ? HistoryExportFormat.JSON
                    : HistoryExportFormat.fromValue(formatValue).orElseThrow(
                            () -> new IllegalArgumentException("Unsupported history export format: " + formatValue)
                    );

            String projectPath = resolveProjectPath();

            LOG.info("[HistoryHandler] SessionId: " + sessionId);
            LOG.info("[HistoryHandler] ProjectPath: " + projectPath);
            LOG.info("[HistoryHandler] CurrentProvider: " + currentProvider);

            HistoryMessageBatch messages = historyProviderRegistry.loadMessages(
                    currentProvider,
                    sessionId,
                    projectPath,
                    payloadBuilder.messageReadPolicy()
            );
            HistoryExportPayload payload = payloadBuilder.build(sessionId, title, messages, format);
            context.dispatchEvent(DownstreamEvent.HISTORY_EXPORT_DATA.value(), payload.json());

            LOG.info("[HistoryHandler] 导出会话完成: exported=" + payload.exportedMessageCount()
                    + ", omitted=" + payload.omittedMessageCount()
                    + ", utf8Bytes=" + payload.utf8Bytes());
        } catch (Exception e) {
            LOG.error("[HistoryHandler] 导出会话失败: " + e.getMessage(), e);
            context.dispatchEvent(
                    DownstreamEvent.HISTORY_EXPORT_DATA.value(),
                    payloadBuilder.buildError(e.getMessage())
            );
        }
    }

    private void printSessionPdf(String content, String currentProvider) {
        LOG.info("[HistoryHandler] ========== 开始打印会话 PDF ==========");
        try {
            JsonObject request = parseRequest(content);
            String sessionId = requiredString(request, HistoryExportPayloadField.SESSION_ID);
            String title = optionalString(request, HistoryExportPayloadField.TITLE);
            String projectPath = resolveProjectPath();

            HistoryMessageBatch messages = historyProviderRegistry.loadMessages(
                    currentProvider,
                    sessionId,
                    projectPath,
                    payloadBuilder.messageReadPolicy()
            );
            // Reuse the bounded HTML renderer: same UTF-8/message budget and the script-free,
            // CSP-locked, fully-escaped transcript as the HTML download.
            HistoryExportPayload payload = payloadBuilder.build(
                    sessionId, title, messages, HistoryExportFormat.HTML);
            String html = extractHtmlContent(payload);

            Path tempFile = writePrintHtmlFile(html, sessionId);
            BrowserUtil.browse(tempFile.toUri());

            LOG.info("[HistoryHandler] 打印 PDF 已在浏览器打开: exported=" + payload.exportedMessageCount()
                    + ", omitted=" + payload.omittedMessageCount()
                    + ", tempFile=" + tempFile);
            dispatchPrintToast(true, null);
        } catch (Exception e) {
            LOG.error("[HistoryHandler] 打印会话 PDF 失败: " + e.getMessage(), e);
            dispatchPrintToast(false, e.getMessage());
        }
    }

    private String resolveProjectPath() {
        String rawPath = context.resolveEffectiveWorkingDirectory();
        String nodePath = NodeDetector.getInstance().getCachedNodePath();
        String projectPath = NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(rawPath)
                : rawPath;
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalStateException("Project path is unavailable");
        }
        return projectPath;
    }

    private void dispatchPrintToast(boolean success, String errorDetail) {
        String message = success
                ? ClaudeCodeGuiBundle.message("file.printPdfOpened")
                : ClaudeCodeGuiBundle.message("file.printPdfFailed");
        DownstreamEvent event = success ? DownstreamEvent.TOAST_SUCCESS : DownstreamEvent.TOAST_ERROR;
        context.dispatchEvent(event.value(), context.escapeJs(message));
    }

    /** Extracts the rendered HTML content carried inside the export envelope. */
    static String extractHtmlContent(HistoryExportPayload payload) {
        JsonObject envelope = GsonHolder.GSON.fromJson(payload.json(), JsonObject.class);
        JsonElement content = envelope.get(HistoryExportPayloadField.CONTENT.wireKey());
        return content == null || content.isJsonNull() ? "" : content.getAsString();
    }

    /**
     * Writes the sanitized HTML transcript to a temp file the system browser can open directly.
     * The file is marked {@code deleteOnExit} so it is cleaned up on IDE restart.
     */
    static Path writePrintHtmlFile(String html, String sessionId) throws IOException {
        String safeId = sanitizeTempId(sessionId);
        Path tempFile = Files.createTempFile("codemoss-history-" + safeId + "-", ".html");
        Files.writeString(tempFile, html == null ? "" : html, StandardCharsets.UTF_8);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    private static String sanitizeTempId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "session";
        }
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < sessionId.length() && sanitized.length() < 16; index++) {
            char current = sessionId.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '-' || current == '_') {
                sanitized.append(current);
            }
        }
        return sanitized.length() == 0 ? "session" : sanitized.toString().toLowerCase(Locale.ROOT);
    }

    private static JsonObject parseRequest(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("History export request is empty");
        }
        JsonElement parsed = GsonHolder.GSON.fromJson(content, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("History export request must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static String requiredString(JsonObject request, HistoryExportPayloadField field) {
        String value = optionalString(request, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field.wireKey() + " is required");
        }
        return value;
    }

    private static String optionalString(JsonObject request, HistoryExportPayloadField field) {
        JsonElement value = request.get(field.wireKey());
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        return value.getAsString().trim();
    }
}
