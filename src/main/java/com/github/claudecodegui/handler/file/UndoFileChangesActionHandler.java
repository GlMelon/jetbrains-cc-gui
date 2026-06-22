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
 * Typed handler for {@code undo_file_changes} action.
 * Reverts a single file to its previous state.
 *
 * @see com.github.claudecodegui.handler.file.UndoFileHandler 旧实现（待删除）
 */
public final class UndoFileChangesActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(UndoFileChangesActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.UNDO_FILE_CHANGES;
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
            String filePath = request.has("filePath") ? request.get("filePath").getAsString() : null;
            String status = request.has("status") ? request.get("status").getAsString() : null;
            JsonArray operations = request.has("operations") ? request.getAsJsonArray("operations") : null;

            if (filePath == null || filePath.isEmpty()) {
                UndoFileUtils.sendError(ctx, filePath, "File path is required");
                return;
            }

            // Security: Validate file path
            if (!UndoFileUtils.isValidFilePath(ctx, filePath)) {
                UndoFileUtils.sendError(ctx, filePath, "Invalid file path: path must be within project directory");
                return;
            }

            if (status == null || status.isEmpty()) {
                UndoFileUtils.sendError(ctx, filePath, "File status is required");
                return;
            }

            LOG.info("[UndoFileChangesActionHandler] Undoing changes for file: " + filePath + ", status: " + status);

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if ("A".equals(status)) {
                        // Added file: delete it
                        UndoFileUtils.deleteFile(ctx, filePath);
                    } else if ("M".equals(status)) {
                        // Modified file: reverse the edits
                        if (operations == null || operations.isEmpty()) {
                            UndoFileUtils.sendError(ctx, filePath, "No operations to undo");
                            return;
                        }
                        UndoFileUtils.reverseEdits(ctx, filePath, operations);
                    } else {
                        UndoFileUtils.sendError(ctx, filePath, "Unknown file status: " + status);
                        return;
                    }

                    // Send success callback
                    UndoFileUtils.sendSuccess(ctx, filePath);

                } catch (Exception e) {
                    LOG.error("[UndoFileChangesActionHandler] Failed to undo file changes: " + e.getMessage(), e);
                    UndoFileUtils.sendError(ctx, filePath, e.getMessage());
                }
            });

        } catch (Exception e) {
            LOG.error("[UndoFileChangesActionHandler] Failed to parse undo request: " + e.getMessage(), e);
            UndoFileUtils.sendError(ctx, null, "Invalid request: " + e.getMessage());
        }
    }
}
