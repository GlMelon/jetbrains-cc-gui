package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.util.AttachmentStorageService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Service for deleting session history files and related data.
 */
class HistoryDeleteService {

    private static final Logger LOG = Logger.getInstance(HistoryDeleteService.class);
    private static final Gson GSON = GsonHolder.GSON;

    // Reject anything outside [A-Za-z0-9._-] to defeat path-traversal payloads such as "../foo"
    // before they reach Path.resolve. Session IDs in both providers are alphanumeric/UUID style.
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;
    private final HistoryLoadService historyLoadService;
    private final HistoryProviderRegistry historyProviderRegistry;

    HistoryDeleteService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller,
                         HistoryLoadService historyLoadService, HistoryProviderRegistry historyProviderRegistry) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
        this.historyLoadService = historyLoadService;
        this.historyProviderRegistry = historyProviderRegistry;
    }

    /**
     * Delete session history files.
     * Deletes the .jsonl file for the specified sessionId and related agent-xxx.jsonl files.
     */
    void handleDeleteSession(String sessionId, String currentProvider) {
        if (!isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== Delete session start ==========");
                LOG.info("[HistoryHandler] SessionId: " + sessionId + ", Provider: " + currentProvider);

                String projectPath = currentProjectPath();
                HistoryDeleteResult result = deleteSessionFiles(sessionId, currentProvider, projectPath);

                LOG.info("[HistoryHandler] Delete completed - Main file: " + (result.mainDeleted() ? "deleted" : "not found") + ", Agent files: " + result.agentFilesDeleted());

                cleanupSessionAttachments(currentProvider, sessionId);
                if (result.mainDeleted()) {
                    cleanupSessionMetadata(sessionId);
                }
                cleanupCache(currentProvider, projectPath);

                LOG.info("[HistoryHandler] Reloading history data...");
                historyLoadService.handleLoadHistoryData(currentProvider);

            } catch (Exception e) {
                LOG.error("[HistoryHandler] Delete session failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Batch delete session history files in one backend request.
     */
    void handleDeleteSessions(String content, String currentProvider) {
        List<String> sessionIds = parseSessionIds(content);
        if (sessionIds.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch delete failed: empty sessionIds");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== Batch delete sessions start ==========");
                LOG.info("[HistoryHandler] SessionIds: " + GSON.toJson(sessionIds) + ", Provider: " + currentProvider);

                int mainDeletedCount = 0;
                int agentFilesDeletedCount = 0;

                for (String sessionId : sessionIds) {
                    try {
                        String projectPath = currentProjectPath();
                        HistoryDeleteResult result = deleteSessionFiles(sessionId, currentProvider, projectPath);
                        cleanupSessionAttachments(currentProvider, sessionId);
                        if (result.mainDeleted()) {
                            mainDeletedCount++;
                            cleanupSessionMetadata(sessionId);
                        }
                        agentFilesDeletedCount += result.agentFilesDeleted();
                    } catch (Exception e) {
                        LOG.error("[HistoryHandler] Batch delete single session failed: " + sessionId + " - " + e.getMessage(), e);
                    }
                }

                cleanupCache(currentProvider, currentProjectPath());

                LOG.info("[HistoryHandler] Batch delete completed - Main files: " + mainDeletedCount + "/" + sessionIds.size()
                        + ", Agent files: " + agentFilesDeletedCount);
                LOG.info("[HistoryHandler] Reloading history data...");
                historyLoadService.handleLoadHistoryData(currentProvider);
            } catch (Exception e) {
                LOG.error("[HistoryHandler] Batch delete sessions failed: " + e.getMessage(), e);
            }
        });
    }

    static List<String> parseSessionIds(String content) {
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonArray()) {
                collectSessionIds(parsed.getAsJsonArray(), sessionIds);
            } else if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                JsonElement sessionIdsElement = object.get("sessionIds");
                if (sessionIdsElement != null && sessionIdsElement.isJsonArray()) {
                    collectSessionIds(sessionIdsElement.getAsJsonArray(), sessionIds);
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Batch delete sessionIds parse failed: " + e.getMessage());
        }

        return new ArrayList<>(sessionIds);
    }

    private static void collectSessionIds(JsonArray array, LinkedHashSet<String> sessionIds) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }

            String sessionId = element.getAsString().trim();
            if (sessionId.isEmpty()) {
                continue;
            }
            if (!isValidSessionId(sessionId)) {
                LOG.warn("[HistoryHandler] Batch delete ignored invalid sessionId");
                continue;
            }
            sessionIds.add(sessionId);
        }
    }

    private HistoryDeleteResult deleteSessionFiles(String sessionId, String currentProvider, String projectPath) throws java.io.IOException {
        if (!isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return HistoryDeleteResult.none();
        }
        return historyProviderRegistry.adapter(currentProvider).deleteSession(sessionId, projectPath);
    }

    static boolean isCodexSessionFileMatch(Path path, String sessionId) {
        return CodexHistoryProviderAdapter.isCodexSessionFileMatch(path, sessionId);
    }

    private void cleanupSessionMetadata(String sessionId) {
        try {
            nodeJsServiceCaller.callNodeJsFavoritesService("removeFavorite", sessionId);
            nodeJsServiceCaller.callNodeJsDeleteTitle(sessionId);
            LOG.info("[HistoryHandler] Cleaned up session metadata");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up metadata (does not affect deletion): " + e.getMessage());
        }
    }

    private void cleanupCache(String currentProvider, String projectPath) {
        try {
            historyProviderRegistry.adapter(currentProvider).clearCache(projectPath);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up cache (does not affect deletion): " + e.getMessage());
        }
    }

    private void cleanupSessionAttachments(String provider, String sessionId) {
        try {
            AttachmentStorageService storageService = AttachmentStorageService.getInstance();
            storageService.deleteSessionRecords(provider, sessionId);
            storageService.cleanupOrphanedResources(storageService.collectAllReferencedHashes());
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up session attachments: " + e.getMessage());
        }
    }

    private String currentProjectPath() {
        return context.getProject() != null ? context.getProject().getBasePath() : null;
    }
}
