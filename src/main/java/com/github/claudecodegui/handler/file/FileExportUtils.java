package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(fileExtension));
            fileDialog.setVisible(true);

            String selectedDir = fileDialog.getDirectory();
            String selectedFile = fileDialog.getFile();

            if (selectedDir != null && selectedFile != null) {
                File fileToSave = new File(selectedDir, selectedFile);
                String path = fileToSave.getAbsolutePath();
                if (!path.toLowerCase().endsWith(fileExtension)) {
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
                notifySuccess(ctx, com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.saved"));
            } catch (IOException e) {
                LOG.error("[FileExport] 保存文件失败: " + e.getMessage(), e);
                String errorDetail = e.getMessage() != null ? e.getMessage() : com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.saveFailed");
                notifyError(ctx, com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.saveFailedWithReason", errorDetail));
            }
        });
    }

    private static void notifySuccess(HandlerContext ctx, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.addToast) { " +
                "  window.addToast('" + ctx.escapeJs(message) + "', 'success'); " +
                "}";
            ctx.executeJavaScriptOnEDT(jsCode);
        });
    }

    private static void notifyError(HandlerContext ctx, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String errorDetail = message != null ? message : com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.unknownError");
            String errorMsg = ctx.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.saveFailedWithReason", errorDetail));
            String jsCode = "if (window.addToast) { " +
                "  window.addToast('" + errorMsg + "', 'error'); " +
                "}";
            ctx.executeJavaScriptOnEDT(jsCode);
        });
    }
}
