package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.model.ConflictStrategy;
import com.github.claudecodegui.model.PromptScope;
import com.github.claudecodegui.settings.AbstractPromptManager;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.watcher.PromptFileWatcher;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Container for prompt action handlers (B2 迁移).
 * Holds shared state and all business logic for prompt CRUD + import/export operations.
 */
public class PromptActionHandlers {

    private static final Logger LOG = Logger.getInstance(PromptActionHandlers.class);
    private static final long MAX_IMPORT_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private final HandlerContext context;
    private final CodemossSettingsService settingsService;
    private final Gson gson;
    private final PromptFileWatcher fileWatcher;

    public PromptActionHandlers(HandlerContext context) {
        this.context = context;
        this.settingsService = context.getSettingsService();
        this.gson = GsonHolder.GSON;

        // Initialize file watcher to monitor .codemoss/prompt.json changes
        this.fileWatcher = new PromptFileWatcher(
            context.getProject(),
            settingsService,
            (scope, promptsJson) -> {
                final String eventType = scope == PromptScope.GLOBAL
                    ? DownstreamEvent.PROMPT_GLOBAL_LIST.value()
                    : DownstreamEvent.PROMPT_PROJECT_LIST.value();
                ApplicationManager.getApplication().invokeLater(() -> {
                    dispatchEvent(eventType, escapeJs(promptsJson));
                });
                LOG.info("[PromptHandler] File watcher triggered update for scope=" + scope.getValue());
            }
        );
        fileWatcher.startWatching();
    }

    /**
     * Cleanup method called when the handler is disposed.
     * Stops the file watcher to prevent memory leaks.
     */
    public void dispose() {
        if (fileWatcher != null) {
            fileWatcher.stopWatching();
        }
    }

    // ── dispatch helpers ──

    private void dispatchEvent(String event, String data) {
        context.dispatchEvent(event, data);
    }

    private String escapeJs(String s) {
        return context.escapeJs(s);
    }

    // ── scope parsing ──

    private PromptScope parseScopeFromData(String data) {
        if (data == null || data.trim().isEmpty()) {
            return PromptScope.GLOBAL;
        }
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            if (json == null || !json.has("scope")) {
                return PromptScope.GLOBAL;
            }
            String scopeStr = json.get("scope").getAsString();
            return PromptScope.fromString(scopeStr);
        } catch (Exception e) {
            LOG.warn("[PromptHandler] Failed to parse scope, defaulting to GLOBAL: " + e.getMessage());
            return PromptScope.GLOBAL;
        }
    }

    // ── business logic ──

    public void handleGetPrompts(String content) {
        try {
            PromptScope scope = parseScopeFromData(content);
            LOG.debug("[PromptHandler] Getting prompts for scope: " + scope.getValue());
            List<JsonObject> prompts = settingsService.getPrompts(scope, context.getProject());
            String promptsJson = gson.toJson(prompts);
            final String eventType = scope == PromptScope.GLOBAL ? DownstreamEvent.PROMPT_GLOBAL_LIST.value() : DownstreamEvent.PROMPT_PROJECT_LIST.value();
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(eventType, escapeJs(promptsJson));
            });
        } catch (IllegalStateException e) {
            PromptScope scope = parseScopeFromData(content);
            if (scope == PromptScope.PROJECT && e.getMessage() != null && e.getMessage().contains("Project not available")) {
                LOG.warn("[PromptHandler] Project not ready yet, skipping project prompts callback");
                return;
            }
            final String eventType = scope == PromptScope.GLOBAL ? DownstreamEvent.PROMPT_GLOBAL_LIST.value() : DownstreamEvent.PROMPT_PROJECT_LIST.value();
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(eventType, escapeJs("[]"));
            });
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to get prompts: " + e.getMessage(), e);
            PromptScope scope = parseScopeFromData(content);
            final String eventType = scope == PromptScope.GLOBAL ? DownstreamEvent.PROMPT_GLOBAL_LIST.value() : DownstreamEvent.PROMPT_PROJECT_LIST.value();
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(eventType, escapeJs("[]"));
            });
        }
    }

    public void handleGetProjectInfo(String content) {
        try {
            JsonObject projectInfo = new JsonObject();
            Project project = context.getProject();
            if (project != null && !project.isDisposed() && project.getBasePath() != null) {
                projectInfo.addProperty("available", true);
                projectInfo.addProperty("name", project.getName());
                projectInfo.addProperty("path", NodeDetector.convertToWslPath(project.getBasePath()));
            } else {
                projectInfo.addProperty("available", false);
                projectInfo.addProperty("name", (String) null);
                projectInfo.addProperty("path", (String) null);
            }
            String projectInfoJson = gson.toJson(projectInfo);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.PROMPT_PROJECT_INFO.value(), escapeJs(projectInfoJson));
            });
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to get project info: " + e.getMessage(), e);
            JsonObject projectInfo = new JsonObject();
            projectInfo.addProperty("available", false);
            projectInfo.addProperty("name", (String) null);
            projectInfo.addProperty("path", (String) null);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent(DownstreamEvent.PROMPT_PROJECT_INFO.value(), escapeJs(gson.toJson(projectInfo)));
            });
        }
    }

    public void handleAddPrompt(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            PromptScope scope = PromptScope.GLOBAL;
            if (data.has("scope")) {
                scope = PromptScope.fromString(data.get("scope").getAsString());
            }
            JsonObject prompt;
            if (data.has("prompt")) {
                prompt = data.getAsJsonObject("prompt");
            } else {
                prompt = data;
            }
            settingsService.addPrompt(prompt, scope, context.getProject());
            final PromptScope finalScope = scope;
            ApplicationManager.getApplication().invokeLater(() -> {
                String scopeJson = "{\"scope\":\"" + finalScope.getValue() + "\"}";
                handleGetPrompts(scopeJson);
                dispatchEvent(DownstreamEvent.PROMPT_OPERATION_RESULT.value(), escapeJs("{\"success\":true,\"operation\":\"add\"}"));
            });
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to add prompt: " + e.getMessage(), e);
            sendErrorResult("add", "Failed to add prompt");
        }
    }

    public void handleUpdatePrompt(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            if (!data.has("id") || data.get("id").isJsonNull()) {
                sendErrorResult("update", "Missing 'id' field in request");
                return;
            }
            if (!data.has("updates") || data.get("updates").isJsonNull()) {
                sendErrorResult("update", "Missing 'updates' field in request");
                return;
            }
            PromptScope scope = PromptScope.GLOBAL;
            if (data.has("scope")) {
                scope = PromptScope.fromString(data.get("scope").getAsString());
            }
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");
            settingsService.updatePrompt(id, updates, scope, context.getProject());
            final PromptScope finalScope = scope;
            ApplicationManager.getApplication().invokeLater(() -> {
                String scopeJson = "{\"scope\":\"" + finalScope.getValue() + "\"}";
                handleGetPrompts(scopeJson);
                dispatchEvent(DownstreamEvent.PROMPT_OPERATION_RESULT.value(), escapeJs("{\"success\":true,\"operation\":\"update\"}"));
            });
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to update prompt: " + e.getMessage(), e);
            sendErrorResult("update", "Failed to update prompt");
        }
    }

    public void handleDeletePrompt(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            if (!data.has("id") || data.get("id").isJsonNull()) {
                sendErrorResult("delete", "Missing 'id' field in request");
                return;
            }
            PromptScope scope = PromptScope.GLOBAL;
            if (data.has("scope")) {
                scope = PromptScope.fromString(data.get("scope").getAsString());
            }
            String id = data.get("id").getAsString();
            boolean deleted = settingsService.deletePrompt(id, scope, context.getProject());
            if (deleted) {
                final PromptScope finalScope = scope;
                ApplicationManager.getApplication().invokeLater(() -> {
                    String scopeJson = "{\"scope\":\"" + finalScope.getValue() + "\"}";
                    handleGetPrompts(scopeJson);
                    dispatchEvent(DownstreamEvent.PROMPT_OPERATION_RESULT.value(), escapeJs("{\"success\":true,\"operation\":\"delete\"}"));
                });
            } else {
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("success", false);
                errorResult.addProperty("operation", "delete");
                errorResult.addProperty("error", "Prompt not found");
                ApplicationManager.getApplication().invokeLater(() -> {
                    dispatchEvent(DownstreamEvent.PROMPT_OPERATION_RESULT.value(), escapeJs(gson.toJson(errorResult)));
                });
            }
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to delete prompt: " + e.getMessage(), e);
            sendErrorResult("delete", "Failed to delete prompt");
        }
    }

    public void handleExportPrompts(String content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                PromptScope scope = parseScopeFromData(content);
                List<JsonObject> prompts = settingsService.getPrompts(scope, context.getProject());
                if (content != null && !content.isEmpty()) {
                    try {
                        JsonObject data = gson.fromJson(content, JsonObject.class);
                        if (data.has("promptIds")) {
                            JsonArray promptIdsArray = data.getAsJsonArray("promptIds");
                            Set<String> selectedIds = new HashSet<>();
                            for (int i = 0; i < promptIdsArray.size(); i++) {
                                selectedIds.add(promptIdsArray.get(i).getAsString());
                            }
                            prompts = prompts.stream()
                                .filter(prompt -> {
                                    String id = prompt.has("id") ? prompt.get("id").getAsString() : "";
                                    return selectedIds.contains(id);
                                })
                                .collect(Collectors.toList());
                        }
                    } catch (Exception ex) {
                        LOG.warn("[PromptHandler] Failed to parse promptIds, exporting all: " + ex.getMessage());
                    }
                }
                if (prompts.isEmpty()) {
                    showNotification("Export Failed", "No prompts to export", NotificationType.WARNING);
                    return;
                }
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String exportTime = dateFormat.format(new Date());
                JsonObject exportData = new JsonObject();
                exportData.addProperty("format", "claude-code-prompts-export-v1");
                exportData.addProperty("exportTime", exportTime);
                exportData.addProperty("promptCount", prompts.size());
                JsonArray promptsArray = new JsonArray();
                for (JsonObject prompt : prompts) {
                    promptsArray.add(prompt);
                }
                exportData.add("prompts", promptsArray);
                FileDialog fileDialog = new FileDialog((Frame) null, "Export Prompts", FileDialog.SAVE);
                SimpleDateFormat filenameDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
                String defaultFilename = "prompts-" + filenameDateFormat.format(new Date()) + ".json";
                fileDialog.setFile(defaultFilename);
                fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".json"));
                fileDialog.setVisible(true);
                String directory = fileDialog.getDirectory();
                String filename = fileDialog.getFile();
                if (directory == null || filename == null) {
                    LOG.info("[PromptHandler] Export cancelled by user");
                    return;
                }
                File file = new File(directory, filename);
                try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                    gson.toJson(exportData, writer);
                    showNotification("Export Successful", "Exported " + prompts.size() + " prompts to " + filename, NotificationType.INFORMATION);
                } catch (Exception ex) {
                    LOG.error("[PromptHandler] Failed to write export file: " + ex.getMessage(), ex);
                    showNotification("Export Failed", "Failed to write file: " + ex.getMessage(), NotificationType.ERROR);
                }
            } catch (Exception e) {
                LOG.error("[PromptHandler] Failed to export prompts: " + e.getMessage(), e);
                showNotification("Export Failed", "Failed to export prompts: " + e.getMessage(), NotificationType.ERROR);
            }
        });
    }

    public void handleImportPromptsFile(String content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                PromptScope scope = parseScopeFromData(content);
                FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
                descriptor.setTitle("Import Prompts");
                descriptor.setDescription("Select a JSON file containing exported prompts");
                descriptor.withFileFilter(vFile -> vFile.getExtension() != null && vFile.getExtension().equalsIgnoreCase("json"));
                VirtualFile initialDir = null;
                String projectPath = context.getProject().getBasePath();
                if (projectPath != null) {
                    initialDir = LocalFileSystem.getInstance().findFileByPath(NodeDetector.toVfsPath(projectPath));
                }
                VirtualFile[] selectedFiles = FileChooser.chooseFiles(descriptor, context.getProject(), initialDir);
                if (selectedFiles.length == 0) {
                    LOG.info("[PromptHandler] Import cancelled by user");
                    return;
                }
                VirtualFile selectedFile = selectedFiles[0];
                File file = new File(selectedFile.getPath());
                long fileSize = file.length();
                if (fileSize > MAX_IMPORT_FILE_SIZE) {
                    showNotification("Import Failed", "File size exceeds 5MB limit", NotificationType.ERROR);
                    return;
                }
                String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JsonObject importData = JsonParser.parseString(fileContent).getAsJsonObject();
                if (!importData.has("format") || !importData.get("format").getAsString().startsWith("claude-code-prompts-export-v")) {
                    showNotification("Import Failed", "Invalid file format. Please select a valid prompts export file.", NotificationType.ERROR);
                    return;
                }
                if (!importData.has("prompts")) {
                    showNotification("Import Failed", "No prompts found in file", NotificationType.ERROR);
                    return;
                }
                JsonArray promptsArray = importData.getAsJsonArray("prompts");
                List<JsonObject> promptsToImport = new ArrayList<>();
                for (int i = 0; i < promptsArray.size(); i++) {
                    promptsToImport.add(promptsArray.get(i).getAsJsonObject());
                }
                AbstractPromptManager promptManager = settingsService.getPromptManager(scope, context.getProject());
                Set<String> conflicts = promptManager.detectConflicts(promptsToImport);
                JsonObject previewData = new JsonObject();
                JsonArray itemsArray = new JsonArray();
                for (JsonObject prompt : promptsToImport) {
                    String validationError = promptManager.validatePrompt(prompt);
                    if (validationError != null) {
                        LOG.warn("[PromptHandler] Invalid prompt in import: " + validationError);
                        continue;
                    }
                    String id = prompt.get("id").getAsString();
                    boolean hasConflict = conflicts.contains(id);
                    JsonObject item = new JsonObject();
                    item.add("data", prompt);
                    item.addProperty("status", hasConflict ? "update" : "new");
                    item.addProperty("conflict", hasConflict);
                    itemsArray.add(item);
                }
                previewData.add("items", itemsArray);
                JsonObject summary = new JsonObject();
                summary.addProperty("total", itemsArray.size());
                summary.addProperty("newCount", (int) itemsArray.asList().stream()
                        .filter(item -> !item.getAsJsonObject().get("conflict").getAsBoolean()).count());
                summary.addProperty("updateCount", conflicts.size());
                previewData.add("summary", summary);
                String previewJson = gson.toJson(previewData);
                dispatchEvent(DownstreamEvent.PROMPT_IMPORT_PREVIEW.value(), escapeJs(previewJson));
            } catch (Exception e) {
                LOG.error("[PromptHandler] Failed to import prompts file: " + e.getMessage(), e);
                showNotification("Import Failed", "Failed to read file: " + e.getMessage(), NotificationType.ERROR);
            }
        });
    }

    public void handleSaveImportedPrompts(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            if (!data.has("prompts") || !data.has("strategy")) {
                sendImportErrorResult("Missing required fields");
                return;
            }
            PromptScope scope = PromptScope.GLOBAL;
            if (data.has("scope")) {
                scope = PromptScope.fromString(data.get("scope").getAsString());
            }
            JsonArray selectedPromptsArray = data.getAsJsonArray("prompts");
            String strategyStr = data.get("strategy").getAsString();
            ConflictStrategy strategy = ConflictStrategy.fromValue(strategyStr);
            List<JsonObject> promptsToImport = new ArrayList<>();
            for (int i = 0; i < selectedPromptsArray.size(); i++) {
                promptsToImport.add(selectedPromptsArray.get(i).getAsJsonObject());
            }
            AbstractPromptManager promptManager = settingsService.getPromptManager(scope, context.getProject());
            Map<String, Object> result = promptManager.batchImportPrompts(promptsToImport, strategy);
            result.put("scope", scope.getValue());
            final PromptScope finalScope = scope;
            ApplicationManager.getApplication().invokeLater(() -> {
                String resultJson = gson.toJson(result);
                dispatchEvent(DownstreamEvent.PROMPT_IMPORT_RESULT.value(), escapeJs(resultJson));
                String scopeJson = "{\"scope\":\"" + finalScope.getValue() + "\"}";
                handleGetPrompts(scopeJson);
                boolean success = Boolean.TRUE.equals(result.get("success"));
                int imported = result.get("imported") instanceof Number ? ((Number) result.get("imported")).intValue() : 0;
                int updated = result.get("updated") instanceof Number ? ((Number) result.get("updated")).intValue() : 0;
                int skipped = result.get("skipped") instanceof Number ? ((Number) result.get("skipped")).intValue() : 0;
                if (success) {
                    String message = String.format("Imported %d prompts (%d new, %d updated, %d skipped)", imported + updated, imported, updated, skipped);
                    showNotification("Import Successful", message, NotificationType.INFORMATION);
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> errors = (List<String>) result.get("errors");
                    String errorMsg = errors.isEmpty() ? "Unknown error" : errors.get(0);
                    showNotification("Import Failed", errorMsg, NotificationType.ERROR);
                }
            });
        } catch (Exception e) {
            LOG.error("[PromptHandler] Failed to save imported prompts: " + e.getMessage(), e);
            sendImportErrorResult("Failed to save prompts: " + e.getMessage());
        }
    }

    // ── helpers ──

    private void sendErrorResult(String operation, String error) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("operation", operation);
        errorResult.addProperty("error", error);
        ApplicationManager.getApplication().invokeLater(() -> {
            dispatchEvent(DownstreamEvent.PROMPT_OPERATION_RESULT.value(), escapeJs(gson.toJson(errorResult)));
        });
    }

    private void sendImportErrorResult(String error) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("error", error);
        ApplicationManager.getApplication().invokeLater(() -> {
            dispatchEvent(DownstreamEvent.PROMPT_IMPORT_RESULT.value(), escapeJs(gson.toJson(errorResult)));
            showNotification("Import Failed", error, NotificationType.ERROR);
        });
    }

    private void showNotification(String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("CC GUI Notifications")
                .createNotification(title, content, type)
                .notify(context.getProject());
    }
}
