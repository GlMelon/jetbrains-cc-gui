package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Typed handler for {@code undo_all_file_changes} action.
 * Batch-reverts multiple files to their previous states.
 *
 * @see com.github.claudecodegui.handler.file.UndoFileHandler 旧实现（待删除）
 */
public final class UndoAllFileChangesActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(UndoAllFileChangesActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.UNDO_ALL_FILE_CHANGES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        try {
            JsonObject request = gson.fromJson(payload, JsonObject.class);
            JsonArray files = request.has("files") ? request.getAsJsonArray("files") : null;

            if (files == null || files.isEmpty()) {
                UndoFileUtils.sendAllError(ctx, "No files to undo");
                return;
            }

            LOG.info("[UndoAllFileChangesActionHandler] Undoing changes for " + files.size() + " files");

            ApplicationManager.getApplication().invokeLater(() -> {
                int successCount = 0;
                int failCount = 0;
                StringBuilder errors = new StringBuilder();

                for (int i = 0; i < files.size(); i++) {
                    JsonObject fileObj = files.get(i).getAsJsonObject();
                    String filePath = fileObj.has("filePath") ? fileObj.get("filePath").getAsString() : null;
                    String status = fileObj.has("status") ? fileObj.get("status").getAsString() : null;
                    JsonArray operations = fileObj.has("operations") ? fileObj.getAsJsonArray("operations") : null;

                    if (filePath == null || filePath.isEmpty()) {
                        failCount++;
                        errors.append("File ").append(i).append(": Missing path; ");
                        continue;
                    }

                    // Security: Validate file path
                    if (!UndoFileUtils.isValidFilePath(ctx, filePath)) {
                        failCount++;
                        errors.append(filePath).append(": Invalid path (outside project); ");
                        continue;
                    }

                    try {
                        if ("A".equals(status)) {
                            // Added file: delete it
                            UndoFileUtils.deleteFile(ctx, filePath);
                        } else if ("M".equals(status)) {
                            // Modified file: reverse the edits
                            if (operations != null && !operations.isEmpty()) {
                                UndoFileUtils.reverseEdits(ctx, filePath, operations);
                            }
                        }
                        successCount++;
                        LOG.info("[UndoAllFileChangesActionHandler] Successfully undone: " + filePath);
                    } catch (Exception e) {
                        failCount++;
                        errors.append(filePath).append(": ").append(e.getMessage()).append("; ");
                        LOG.error("[UndoAllFileChangesActionHandler] Failed to undo " + filePath + ": " + e.getMessage(), e);
                    }
                }

                if (failCount == 0) {
                    UndoFileUtils.sendAllSuccess(ctx, successCount);
                } else if (successCount > 0) {
                    // Partial success
                    UndoFileUtils.sendAllSuccess(ctx, successCount);
                } else {
                    UndoFileUtils.sendAllError(ctx, errors.toString());
                }
            });

        } catch (Exception e) {
            LOG.error("[UndoAllFileChangesActionHandler] Failed to parse batch undo request: " + e.getMessage(), e);
            UndoFileUtils.sendAllError(ctx, "Invalid request: " + e.getMessage());
        }
    }
}
