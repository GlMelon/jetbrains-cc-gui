package com.github.claudecodegui.handler.rewind;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.provider.claude.ClaudeRewindService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Restores Claude CLI file checkpoints for a selected user message.
 */
public final class RewindFilesActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(RewindFilesActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    private final ClaudeRewindService rewindService;

    public RewindFilesActionHandler() {
        this(new ClaudeRewindService());
    }

    RewindFilesActionHandler(ClaudeRewindService rewindService) {
        this.rewindService = rewindService;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.REWIND_FILES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext handlerContext = context.handlerContext();
        CompletableFuture.runAsync(() -> handleAsync(payload, handlerContext),
                AppExecutorUtil.getAppExecutorService());
    }

    private void handleAsync(String payload, HandlerContext context) {
        try {
            JsonObject request = gson.fromJson(payload, JsonObject.class);
            String sessionId = getString(request, "sessionId");
            String userMessageId = getString(request, "userMessageId");

            if (!isStrictUuid(sessionId)) {
                dispatchError(context, "INVALID_SESSION_ID", "A valid Claude session ID is required");
                return;
            }
            if (!isStrictUuid(userMessageId)) {
                dispatchError(context, "INVALID_USER_MESSAGE_ID", "A valid Claude user message ID is required");
                return;
            }

            String cwd = resolveWorkingDirectory(context);
            if (hasUnsavedDocumentsUnder(cwd)) {
                dispatchError(
                        context,
                        "UNSAVED_DOCUMENTS",
                        "Save modified project files before restoring a Claude checkpoint"
                );
                return;
            }

            rewindService.rewindFiles(sessionId, userMessageId, cwd)
                    .thenAccept(result -> dispatchResult(context, cwd, normalizeResult(result)))
                    .exceptionally(error -> {
                        LOG.warn("[Rewind] File rewind failed", error);
                        dispatchError(
                                context,
                                "PROCESS_FAILED",
                                error.getMessage() == null ? "Claude file rewind failed" : error.getMessage()
                        );
                        return null;
                    });
        } catch (Exception error) {
            LOG.warn("[Rewind] Invalid rewind request", error);
            dispatchError(context, "INVALID_REQUEST", "Invalid rewind request");
        }
    }

    private static JsonObject normalizeResult(JsonObject result) {
        JsonObject normalized = result == null ? new JsonObject() : result.deepCopy();
        boolean success = normalized.has("success") && normalized.get("success").getAsBoolean();
        normalized.addProperty("success", success);
        if (!success && !normalized.has("message")) {
            String error = normalized.has("error")
                    ? normalized.get("error").getAsString()
                    : "Claude file rewind failed";
            normalized.addProperty("message", error);
        }
        return normalized;
    }

    private static void dispatchResult(HandlerContext context, String cwd, JsonObject result) {
        boolean success = result.has("success") && result.get("success").getAsBoolean();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (success) {
                refreshWorkingDirectory(cwd);
            }
            context.dispatchEvent(
                    DownstreamEvent.REWIND_RESULT.value(),
                    context.escapeJs(gson.toJson(result))
            );
        });
    }

    private static void dispatchError(HandlerContext context, String errorCode, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("errorCode", errorCode);
        result.addProperty("message", message);
        dispatchResult(context, null, result);
    }

    private static String resolveWorkingDirectory(HandlerContext context) {
        if (context.getSession() != null) {
            String cwd = context.getSession().getCwd();
            if (cwd != null && !cwd.isBlank()) {
                return cwd;
            }
        }
        Project project = context.getProject();
        return project == null ? null : project.getBasePath();
    }

    private static boolean hasUnsavedDocumentsUnder(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return false;
        }
        Path root;
        try {
            root = Path.of(cwd).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            LOG.debug("[Rewind] Unable to inspect unsaved documents for cwd: " + cwd, error);
            return false;
        }
        // FileDocumentManager is an EDT-only API; this handler runs on a background thread.
        AtomicBoolean hasUnsaved = new AtomicBoolean(false);
        ApplicationManager.getApplication().invokeAndWait(
                () -> hasUnsaved.set(findUnsavedDocumentUnder(root)));
        return hasUnsaved.get();
    }

    private static boolean findUnsavedDocumentUnder(Path root) {
        FileDocumentManager documents = FileDocumentManager.getInstance();
        for (com.intellij.openapi.editor.Document document : documents.getUnsavedDocuments()) {
            VirtualFile file = documents.getFile(document);
            if (file == null || !file.isInLocalFileSystem()) {
                continue;
            }
            try {
                Path filePath = Path.of(file.getPath()).toAbsolutePath().normalize();
                if (filePath.startsWith(root)) {
                    return true;
                }
            } catch (InvalidPathException error) {
                LOG.debug("[Rewind] Skipping unsaved document with unparsable path: " + file.getPath(), error);
            }
        }
        return false;
    }

    private static void refreshWorkingDirectory(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return;
        }
        // refreshAndFindFileByPath requires forward slashes; project/session cwds on
        // Windows carry backslashes (see NodeDetector#toVfsPath for the same pattern).
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                com.github.claudecodegui.util.WslPathUtil.toVfsPath(cwd));
        if (root == null) {
            root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new java.io.File(cwd));
        }
        if (root != null) {
            root.refresh(false, true);
        }
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String value = object.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean isStrictUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
