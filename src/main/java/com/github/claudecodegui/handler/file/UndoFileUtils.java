package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.WslPathUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Undo file changes shared utilities.
 * Extracted from {@link UndoFileHandler} during V9 typed handler migration.
 */
final class UndoFileUtils {

    private static final Logger LOG = Logger.getInstance(UndoFileUtils.class);
    private static final Gson gson = GsonHolder.GSON;

    private UndoFileUtils() {}

    static boolean isValidFilePath(HandlerContext ctx, String filePath) {
        String projectBasePath = ctx.getProject() != null ? ctx.getProject().getBasePath() : null;
        if (projectBasePath == null) {
            LOG.warn("[UndoFile] Cannot validate path: project base path is null");
            return false;
        }
        boolean isValid = WslPathUtil.isPathWithinDirectory(filePath, projectBasePath);
        if (!isValid) {
            LOG.warn("[UndoFile] File path outside project directory: " + filePath);
        }
        return isValid;
    }

    static void deleteFile(HandlerContext ctx, String filePath) throws Exception {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(WslPathUtil.toVfsPath(filePath));
        if (file == null || !file.exists()) {
            LOG.warn("[UndoFile] File not found for deletion: " + filePath);
            // File already doesn't exist, treat as success
            return;
        }

        final AtomicReference<Exception> exceptionRef = new AtomicReference<>();

        WriteCommandAction.runWriteCommandAction(ctx.getProject(), "Undo Claude: Delete File", null, () -> {
            try {
                file.delete(UndoFileUtils.class);
                LOG.info("[UndoFile] Successfully deleted file: " + filePath);
            } catch (IOException e) {
                exceptionRef.set(e);
            }
        });

        Exception ex = exceptionRef.get();
        if (ex != null) {
            throw new Exception("Failed to delete file: " + ex.getMessage(), ex);
        }
    }

    static void reverseEdits(HandlerContext ctx, String filePath, JsonArray operations) throws Exception {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(WslPathUtil.toVfsPath(filePath));
        if (file == null || !file.exists()) {
            throw new Exception("File not found: " + filePath);
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            throw new Exception("Cannot get document for: " + filePath);
        }

        WriteCommandAction.runWriteCommandAction(ctx.getProject(), "Undo Claude Changes", null, () -> {
            String content = document.getText();

            // Reverse iterate through operations to undo in correct order
            // Each operation: replace newString back to oldString
            for (int i = operations.size() - 1; i >= 0; i--) {
                JsonObject op = operations.get(i).getAsJsonObject();
                String oldString = op.has("oldString") && !op.get("oldString").isJsonNull()
                    ? op.get("oldString").getAsString()
                    : "";
                String newString = op.has("newString") && !op.get("newString").isJsonNull()
                    ? op.get("newString").getAsString()
                    : "";
                boolean replaceAll = op.has("replaceAll") && op.get("replaceAll").getAsBoolean();

                if (newString.isEmpty()) {
                    LOG.warn("[UndoFile] Skipping operation with empty newString (deletion case)");
                    continue;
                }

                if (replaceAll) {
                    content = content.replace(newString, oldString);
                } else {
                    int index = content.indexOf(newString);
                    if (index != -1) {
                        content = content.substring(0, index) + oldString + content.substring(index + newString.length());
                    } else {
                        LOG.warn("[UndoFile] Could not find newString to replace: " +
                            newString.substring(0, Math.min(50, newString.length())) + "...");
                    }
                }
            }

            document.setText(content);
        });

        FileDocumentManager.getInstance().saveDocument(document);
        file.refresh(false, false);

        LOG.info("[UndoFile] Successfully reversed edits for file: " + filePath);
    }

    static void sendSuccess(HandlerContext ctx, String filePath) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("filePath", filePath != null ? filePath : "");

        String json = gson.toJson(result);
        LOG.info("[UndoFile] Sending success callback: " + json);

        ApplicationManager.getApplication().invokeLater(() -> {
            ctx.callJavaScript("onUndoFileResult", ctx.escapeJs(json));
        });
    }

    static void sendError(HandlerContext ctx, String filePath, String error) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("filePath", filePath != null ? filePath : "");
        result.addProperty("error", error);

        String json = gson.toJson(result);
        LOG.warn("[UndoFile] Sending error callback: " + json);

        ApplicationManager.getApplication().invokeLater(() -> {
            ctx.callJavaScript("onUndoFileResult", ctx.escapeJs(json));
        });
    }

    static void sendAllSuccess(HandlerContext ctx, int count) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("count", count);

        String json = gson.toJson(result);
        LOG.info("[UndoFile] Sending batch success callback: " + json);

        ApplicationManager.getApplication().invokeLater(() -> {
            ctx.callJavaScript("onUndoAllFileResult", ctx.escapeJs(json));
        });
    }

    static void sendAllError(HandlerContext ctx, String error) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", error);

        String json = gson.toJson(result);
        LOG.warn("[UndoFile] Sending batch error callback: " + json);

        ApplicationManager.getApplication().invokeLater(() -> {
            ctx.callJavaScript("onUndoAllFileResult", ctx.escapeJs(json));
        });
    }
}
