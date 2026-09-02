package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.protocol.payload.HistoryExportLimits;
import com.github.claudecodegui.protocol.payload.HistoryExportPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * File export shared utilities.
 * Extracted from {@link FileExportHandler} during V9 typed handler migration.
 */
final class FileExportUtils {

    private static final Logger LOG = Logger.getInstance(FileExportUtils.class);
    private static final Gson gson = GsonHolder.GSON;

    private FileExportUtils() {}


    /**
     * Saves backend-rendered history exports without trusting frontend format metadata.
     */
    static void handleSaveExportedFile(HandlerContext ctx, String jsonContent) {
        try {
            JsonObject json = parseObject(jsonContent);
            String content = requiredString(json, HistoryExportPayloadField.CONTENT);
            String requestedFileName = requiredString(json, HistoryExportPayloadField.FILE_NAME);
            String formatValue = requiredString(json, HistoryExportPayloadField.FORMAT);
            HistoryExportFormat format = HistoryExportFormat.fromValue(formatValue).orElseThrow(
                    () -> new IllegalArgumentException("Unsupported history export format: " + formatValue)
            );
            if (!format.matchesFileName(requestedFileName)) {
                throw new IllegalArgumentException("History export file extension does not match format");
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > HistoryExportLimits.DEFAULT_MAX_UTF8_BYTES) {
                throw new IllegalArgumentException("History export content exceeds configured UTF-8 byte limit");
            }

            String fileName = sanitizeFileName(requestedFileName, format);
            String dialogTitle = ClaudeCodeGuiBundle.message(format.dialogTitleKey());
            ApplicationManager.getApplication().invokeLater(() -> {
                File selectedFile = showSaveDialog(ctx, dialogTitle, format.fileExtension(), fileName);
                if (selectedFile != null) {
                    writeFileAsync(ctx, selectedFile, content);
                }
            });
        } catch (Exception e) {
            LOG.error("[FileExport] 处理历史导出保存请求失败: " + e.getMessage(), e);
            notifyError(ctx, e.getMessage());
        }
    }

    /**
     * Handle saving a file (supports multiple formats).
     */
    static void handleSaveFile(HandlerContext ctx, String jsonContent, String fileExtension, String dialogTitle) {
        try {
            LOG.info("[FileExport] ========== 开始保存文件 ==========");
            LOG.info("[FileExport] 文件类型: " + fileExtension);

            JsonObject json = gson.fromJson(jsonContent, JsonObject.class);
            String content = json.get("content").getAsString();
            String filename = json.get("filename").getAsString();

            LOG.info("[FileExport] 文件名: " + filename);

            ApplicationManager.getApplication().invokeLater(() -> {
                File selectedFile = showSaveDialog(ctx, dialogTitle, fileExtension, filename);
                if (selectedFile != null) {
                    writeFileAsync(ctx, selectedFile, content);
                } else {
                    LOG.info("[FileExport] 用户取消了保存");
                }
                LOG.info("[FileExport] ========== 保存文件完成 ==========");
            });

        } catch (Exception e) {
            LOG.error("[FileExport] 处理保存请求失败: " + e.getMessage(), e);
            notifyError(ctx, e.getMessage());
        }
    }

    private static File showSaveDialog(HandlerContext ctx, String dialogTitle, String fileExtension, String filename) {
        try {
            String projectPath = ctx.getProject().getBasePath();
            FileDialog fileDialog = new FileDialog((Frame) null, dialogTitle, FileDialog.SAVE);

            if (projectPath != null) {
                fileDialog.setDirectory(projectPath);
            }
            fileDialog.setFile(filename);
            fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(fileExtension));
            fileDialog.setVisible(true);

            String selectedDir = fileDialog.getDirectory();
            String selectedFile = fileDialog.getFile();

            if (selectedDir != null && selectedFile != null) {
                File fileToSave = new File(selectedDir, selectedFile);
                String path = fileToSave.getAbsolutePath();
                if (!path.toLowerCase(Locale.ROOT).endsWith(fileExtension)) {
                    fileToSave = new File(path + fileExtension);
                }
                return fileToSave;
            }
            return null;
        } catch (Exception e) {
            LOG.error("[FileExport] 显示对话框失败: " + e.getMessage(), e);
            notifyError(ctx, e.getMessage());
            return null;
        }
    }

    private static void writeFileAsync(HandlerContext ctx, File fileToSave, String content) {
        CompletableFuture.runAsync(() -> {
            try (FileWriter writer = new FileWriter(fileToSave, StandardCharsets.UTF_8)) {
                writer.write(content);
                LOG.info("[FileExport] 文件保存成功: " + fileToSave.getAbsolutePath());
                notifySuccess(ctx, ClaudeCodeGuiBundle.message("file.saved"));
            } catch (IOException e) {
                LOG.error("[FileExport] 保存文件失败: " + e.getMessage(), e);
                String errorDetail = e.getMessage() != null ? e.getMessage() : ClaudeCodeGuiBundle.message("file.saveFailed");
                notifyError(ctx, ClaudeCodeGuiBundle.message("file.saveFailedWithReason", errorDetail));
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private static JsonObject parseObject(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            throw new IllegalArgumentException("History export save request is empty");
        }
        JsonElement parsed = gson.fromJson(jsonContent, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("History export save request must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static String requiredString(JsonObject json, HistoryExportPayloadField field) {
        JsonElement value = json.get(field.wireKey());
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field.wireKey() + " is required");
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw new IllegalArgumentException(field.wireKey() + " is required");
        }
        return text;
    }

    private static String sanitizeFileName(String requestedFileName, HistoryExportFormat format) {
        StringBuilder sanitized = new StringBuilder(requestedFileName.length());
        for (int index = 0; index < requestedFileName.length(); index++) {
            char current = requestedFileName.charAt(index);
            if (current < 32 || current == '<' || current == '>' || current == ':' || current == '"'
                    || current == '/' || current == '\\' || current == '|' || current == '?' || current == '*') {
                sanitized.append('_');
            } else {
                sanitized.append(current);
            }
        }
        String fileName = sanitized.toString().trim();
        if (fileName.isBlank()) {
            fileName = "session" + format.fileExtension();
        }
        if (!format.matchesFileName(fileName)) {
            fileName += format.fileExtension();
        }
        return fileName;
    }

    private static void notifySuccess(HandlerContext ctx, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent(DownstreamEvent.TOAST_SUCCESS.value(), message));
    }

    private static void notifyError(HandlerContext ctx, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String errorDetail = message != null ? message : ClaudeCodeGuiBundle.message("file.unknownError");
            String errorMessage = ClaudeCodeGuiBundle.message("file.saveFailedWithReason", errorDetail);
            ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMessage);
        });
    }
}
