package com.github.claudecodegui.handler.agent;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.ConflictStrategy;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Container for agent action handlers (B2 迁移).
 * Holds shared state and all business logic for agent CRUD operations.
 */
public class AgentActionHandlers {

    private static final Logger LOG = Logger.getInstance(AgentActionHandlers.class);

    private final HandlerContext context;
    private final CodemossSettingsService settingsService;
    private final Gson gson;

    public AgentActionHandlers(HandlerContext context) {
        this.context = context;
        this.settingsService = CodemossSettingsService.getInstance();
        this.gson = GsonHolder.GSON;
    }

    // ── dispatch helpers ──

    private void dispatchEvent(String event, String data) {
        context.dispatchEvent(event, data);
    }

    private String escapeJs(String s) {
        return context.escapeJs(s);
    }

    // ── business logic ──

    public void handleGetAgents() {
        try {
            List<JsonObject> agents = settingsService.getAgents();
            String agentsJson = gson.toJson(agents);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.list", escapeJs(agentsJson));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to get agents: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.list", escapeJs("[]"));
            });
        }
    }

    public void handleAddAgent(String content) {
        try {
            JsonObject agent = gson.fromJson(content, JsonObject.class);
            settingsService.addAgent(agent);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                dispatchEvent("agent.operation_result", escapeJs("{\"success\":true,\"operation\":\"add\"}"));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to add agent: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("operation", "add");
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.operation_result", escapeJs(gson.toJson(errorResult)));
            });
        }
    }

    public void handleUpdateAgent(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");
            settingsService.updateAgent(id, updates);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                dispatchEvent("agent.operation_result", escapeJs("{\"success\":true,\"operation\":\"update\"}"));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to update agent: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("operation", "update");
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.operation_result", escapeJs(gson.toJson(errorResult)));
            });
        }
    }

    public void handleDeleteAgent(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            boolean deleted = settingsService.deleteAgent(id);
            if (deleted) {
                try {
                    String selectedId = settingsService.getSelectedAgentId();
                    if (id.equals(selectedId)) {
                        settingsService.setSelectedAgentId(null);
                        dispatchEvent("agent.selected_changed", escapeJs("null"));
                    }
                } catch (Exception ex) {
                    LOG.warn("[AgentHandler] Failed to check/clear selected agent: " + ex.getMessage());
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    handleGetAgents();
                    dispatchEvent("agent.operation_result", escapeJs("{\"success\":true,\"operation\":\"delete\"}"));
                });
            } else {
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("success", false);
                errorResult.addProperty("operation", "delete");
                errorResult.addProperty("error", "Agent not found");
                ApplicationManager.getApplication().invokeLater(() -> {
                    dispatchEvent("agent.operation_result", escapeJs(gson.toJson(errorResult)));
                });
            }
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to delete agent: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("operation", "delete");
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.operation_result", escapeJs(gson.toJson(errorResult)));
            });
        }
    }

    public void handleGetSelectedAgent() {
        try {
            String selectedId = settingsService.getSelectedAgentId();
            JsonObject result = new JsonObject();
            if (selectedId != null && !selectedId.isEmpty()) {
                JsonObject agent = settingsService.getAgent(selectedId);
                if (agent != null) {
                    result.addProperty("selectedAgentId", selectedId);
                    result.add("agent", agent);
                } else {
                    settingsService.setSelectedAgentId(null);
                    result.addProperty("selectedAgentId", (String) null);
                }
            } else {
                result.addProperty("selectedAgentId", (String) null);
            }
            String resultJson = gson.toJson(result);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.selected_received", escapeJs(resultJson));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to get selected agent: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.selected_received", escapeJs("{\"selectedAgentId\":null}"));
            });
        }
    }

    public void handleSetSelectedAgent(String content) {
        try {
            String agentId = null;
            if (content != null && !content.isEmpty() && !content.equals("null")) {
                JsonObject data = gson.fromJson(content, JsonObject.class);
                if (data != null) {
                    if (data.has("id") && !data.get("id").isJsonNull()) {
                        agentId = data.get("id").getAsString();
                    } else if (data.has("agentId") && !data.get("agentId").isJsonNull()) {
                        agentId = data.get("agentId").getAsString();
                    }
                }
            }
            LOG.info("[AgentHandler] Setting selected agent: " + (agentId != null ? agentId : "null"));
            settingsService.setSelectedAgentId(agentId);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            if (agentId != null) {
                JsonObject agent = settingsService.getAgent(agentId);
                if (agent != null) {
                    result.add("agent", agent);
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown Agent";
                    ClaudeNotifier.setAgent(context.getProject(), agentName);
                } else {
                    ClaudeNotifier.setAgent(context.getProject(), "");
                }
            } else {
                ClaudeNotifier.setAgent(context.getProject(), "");
            }
            String resultJson = gson.toJson(result);
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.selected_changed", escapeJs(resultJson));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to set selected agent: " + e.getMessage(), e);
            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("success", false);
            errorResult.addProperty("error", e.getMessage());
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("agent.selected_changed", escapeJs(gson.toJson(errorResult)));
            });
        }
    }

    public void handleExportAgents(String content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                List<JsonObject> agents = settingsService.getAgents();
                if (content != null && !content.isEmpty()) {
                    try {
                        JsonObject data = gson.fromJson(content, JsonObject.class);
                        if (data.has("agentIds")) {
                            JsonArray agentIdsArray = data.getAsJsonArray("agentIds");
                            HashSet<String> selectedIds = new HashSet<>();
                            for (int i = 0; i < agentIdsArray.size(); i++) {
                                selectedIds.add(agentIdsArray.get(i).getAsString());
                            }
                            agents = agents.stream()
                                .filter(agent -> {
                                    String id = agent.has("id") ? agent.get("id").getAsString() : "";
                                    return selectedIds.contains(id);
                                })
                                .collect(Collectors.toList());
                        }
                    } catch (Exception ex) {
                        LOG.warn("[AgentHandler] Failed to parse agentIds, exporting all: " + ex.getMessage());
                    }
                }
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String exportTime = dateFormat.format(new Date());
                JsonObject exportData = new JsonObject();
                exportData.addProperty("format", "claude-code-agents-export-v1");
                exportData.addProperty("exportTime", exportTime);
                exportData.addProperty("agentCount", agents.size());
                JsonArray agentsArray = new JsonArray();
                for (JsonObject agent : agents) {
                    agentsArray.add(agent);
                }
                exportData.add("agents", agentsArray);
                String projectPath = context.getProject().getBasePath();
                FileDialog fileDialog = new FileDialog((Frame) null, "Export Agents", FileDialog.SAVE);
                if (projectPath != null) {
                    fileDialog.setDirectory(projectPath);
                }
                SimpleDateFormat filenameDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
                String defaultFilename = "agents-" + filenameDateFormat.format(new Date()) + ".json";
                fileDialog.setFile(defaultFilename);
                fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".json"));
                fileDialog.setVisible(true);
                String selectedDir = fileDialog.getDirectory();
                String selectedFile = fileDialog.getFile();
                if (selectedDir != null && selectedFile != null) {
                    File fileToSave = new File(selectedDir, selectedFile);
                    String path = fileToSave.getAbsolutePath();
                    if (!path.toLowerCase().endsWith(".json")) {
                        fileToSave = new File(path + ".json");
                    }
                    try (FileWriter writer = new FileWriter(fileToSave, StandardCharsets.UTF_8)) {
                        gson.toJson(exportData, writer);
                        LOG.info("[AgentHandler] Successfully exported " + agents.size() + " agents to: " + fileToSave.getAbsolutePath());
                        ClaudeNotifier.showSuccess(context.getProject(), "Exported " + agents.size() + " agents to " + fileToSave.getName());
                    }
                } else {
                    LOG.info("[AgentHandler] Export cancelled by user");
                }
            } catch (Exception e) {
                LOG.error("[AgentHandler] Failed to export agents: " + e.getMessage(), e);
                ClaudeNotifier.showError(context.getProject(), "Failed to export agents: " + e.getMessage());
            }
        });
    }

    public void handleImportAgentsFile() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
                descriptor.setTitle("Import Agents");
                descriptor.setDescription("Select a JSON file containing exported agents");
                descriptor.withFileFilter(file -> file.getExtension() != null && file.getExtension().equalsIgnoreCase("json"));
                VirtualFile initialDir = null;
                String projectPath = context.getProject().getBasePath();
                if (projectPath != null) {
                    initialDir = LocalFileSystem.getInstance().findFileByPath(NodeDetector.toVfsPath(projectPath));
                }
                VirtualFile[] selectedFiles = FileChooser.chooseFiles(descriptor, context.getProject(), initialDir);
                if (selectedFiles.length > 0) {
                    VirtualFile selectedFile = selectedFiles[0];
                    File fileToImport = new File(selectedFile.getPath());
                    if (!fileToImport.exists() || !fileToImport.canRead()) {
                        throw new Exception("File not found or cannot be read: " + fileToImport.getAbsolutePath());
                    }
                    long fileSize = fileToImport.length();
                    if (fileSize > 5 * 1024 * 1024) {
                        throw new Exception("File too large (> 5MB). Please reduce the number of items.");
                    }
                    String fileContent = new String(Files.readAllBytes(fileToImport.toPath()), StandardCharsets.UTF_8);
                    JsonObject importData = gson.fromJson(fileContent, JsonObject.class);
                    if (!importData.has("format") || !importData.get("format").getAsString().equals("claude-code-agents-export-v1")) {
                        throw new Exception("Invalid file format. Expected claude-code-agents-export-v1");
                    }
                    if (!importData.has("agents")) {
                        throw new Exception("Invalid file: missing 'agents' field");
                    }
                    JsonArray agentsArray = importData.getAsJsonArray("agents");
                    java.util.ArrayList<JsonObject> agentsToImport = new java.util.ArrayList<>();
                    for (int i = 0; i < agentsArray.size(); i++) {
                        agentsToImport.add(agentsArray.get(i).getAsJsonObject());
                    }
                    java.util.Set<String> conflicts = settingsService.getAgentManager().detectConflicts(agentsToImport);
                    JsonObject previewResult = new JsonObject();
                    JsonArray previewItems = new JsonArray();
                    for (JsonObject agent : agentsToImport) {
                        JsonObject previewItem = new JsonObject();
                        previewItem.add("data", agent);
                        String id = agent.has("id") ? agent.get("id").getAsString() : "";
                        boolean hasConflict = conflicts.contains(id);
                        previewItem.addProperty("status", hasConflict ? "update" : "new");
                        previewItem.addProperty("conflict", hasConflict);
                        previewItems.add(previewItem);
                    }
                    previewResult.add("items", previewItems);
                    JsonObject summary = new JsonObject();
                    summary.addProperty("total", agentsToImport.size());
                    summary.addProperty("newCount", agentsToImport.size() - conflicts.size());
                    summary.addProperty("updateCount", conflicts.size());
                    previewResult.add("summary", summary);
                    String resultJson = gson.toJson(previewResult);
                    dispatchEvent("agent.import_preview", escapeJs(resultJson));
                } else {
                    LOG.info("[AgentHandler] Import cancelled by user");
                }
            } catch (Exception e) {
                LOG.error("[AgentHandler] Failed to import agents file: " + e.getMessage(), e);
                ClaudeNotifier.showError(context.getProject(), "Failed to load import file: " + e.getMessage());
            }
        });
    }

    public void handleSaveImportedAgents(String content) {
        try {
            JsonObject data = gson.fromJson(content, JsonObject.class);
            if (!data.has("agents") || !data.has("strategy")) {
                throw new Exception("Missing required fields: agents or strategy");
            }
            JsonArray agentsArray = data.getAsJsonArray("agents");
            String strategyValue = data.get("strategy").getAsString();
            ConflictStrategy strategy = ConflictStrategy.fromValue(strategyValue);
            java.util.ArrayList<JsonObject> agentsToImport = new java.util.ArrayList<>();
            for (int i = 0; i < agentsArray.size(); i++) {
                agentsToImport.add(agentsArray.get(i).getAsJsonObject());
            }
            Map<String, Object> result = settingsService.getAgentManager().batchImportAgents(agentsToImport, strategy);
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetAgents();
                int imported = (int) result.get("imported");
                int updated = (int) result.get("updated");
                int skipped = (int) result.get("skipped");
                String message = String.format("Imported %d agents (%d new, %d updated, %d skipped)", imported + updated, imported, updated, skipped);
                ClaudeNotifier.showSuccess(context.getProject(), message);
                JsonObject importResult = new JsonObject();
                importResult.addProperty("success", (boolean) result.get("success"));
                importResult.addProperty("imported", imported);
                importResult.addProperty("updated", updated);
                importResult.addProperty("skipped", skipped);
                dispatchEvent("agent.import_result", escapeJs(gson.toJson(importResult)));
            });
        } catch (Exception e) {
            LOG.error("[AgentHandler] Failed to save imported agents: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                ClaudeNotifier.showError(context.getProject(), "Failed to import agents: " + e.getMessage());
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("success", false);
                errorResult.addProperty("error", e.getMessage());
                dispatchEvent("agent.import_result", escapeJs(gson.toJson(errorResult)));
            });
        }
    }
}
