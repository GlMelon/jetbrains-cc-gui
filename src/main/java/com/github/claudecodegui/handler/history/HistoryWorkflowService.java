package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.AttachmentStorageService;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Unified workflow for history list refresh, deletion, metadata cleanup, and cache invalidation.
 * Provider-specific storage details remain inside {@link HistoryProviderAdapter} implementations.
 */
class HistoryWorkflowService {
    private static final Logger LOG = Logger.getInstance(HistoryWorkflowService.class);

    private final HistoryProviderRegistry providerRegistry;
    private final HistoryProjectPathResolver projectPathResolver;
    private final HistorySessionsJsonEnhancer sessionsJsonEnhancer;
    private final HistoryLoadService historyLoadService;
    private final NodeJsServiceCaller nodeJsServiceCaller;
    private final HandlerContext context;
    private final Supplier<String> projectPathSupplier;
    private final BiConsumer<String, String> attachmentCleanup;
    private final Consumer<String> metadataCleanup;

    HistoryWorkflowService(HandlerContext context,
                           HistoryProviderRegistry providerRegistry,
                           HistoryProjectPathResolver projectPathResolver,
                           HistorySessionsJsonEnhancer sessionsJsonEnhancer,
                           HistoryLoadService historyLoadService,
                           NodeJsServiceCaller nodeJsServiceCaller) {
        this(context, providerRegistry, projectPathResolver, sessionsJsonEnhancer, historyLoadService,
                nodeJsServiceCaller, () -> projectPathResolver.resolve(context), null, null);
    }

    HistoryWorkflowService(HandlerContext context,
                           HistoryProviderRegistry providerRegistry,
                           HistoryProjectPathResolver projectPathResolver,
                           HistorySessionsJsonEnhancer sessionsJsonEnhancer,
                           HistoryLoadService historyLoadService,
                           NodeJsServiceCaller nodeJsServiceCaller,
                           Supplier<String> projectPathSupplier,
                           BiConsumer<String, String> attachmentCleanup,
                           Consumer<String> metadataCleanup) {
        this.context = context;
        this.providerRegistry = providerRegistry;
        this.projectPathResolver = projectPathResolver;
        this.sessionsJsonEnhancer = sessionsJsonEnhancer;
        this.historyLoadService = historyLoadService;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
        this.projectPathSupplier = projectPathSupplier;
        this.attachmentCleanup = attachmentCleanup;
        this.metadataCleanup = metadataCleanup;
    }

    void refresh(String provider) {
        LOG.info("[HistoryHandler] ========== 开始加载历史数据 ========== provider=" + provider);
        try {
            String projectPath = resolveProjectPath();
            if (projectPath == null) {
                LOG.warn("[HistoryHandler] Project base path is null");
                historyLoadService.dispatchHistoryDataError("Project base path is null");
                return;
            }

            HistoryProviderAdapter adapter = providerRegistry.adapter(provider);
            LOG.info("[HistoryHandler] 使用 history provider 读取会话: " + adapter.provider().value());
            String historyJson = adapter.loadSessionsJson(projectPath);
            HistoryCapabilities capabilities = HistoryCapabilities.from(adapter);
            String finalJson = sessionsJsonEnhancer.enhance(adapter.provider().value(), historyJson, capabilities);
            LOG.info("[HistoryHandler] 历史数据增强完成，JSON 长度: " + finalJson.length());
            historyLoadService.dispatchHistoryData(finalJson);
        } catch (Exception e) {
            LOG.error("[HistoryHandler] 加载历史数据失败: " + e.getMessage(), e);
            historyLoadService.dispatchHistoryDataError(e.getMessage());
        }
    }

    HistoryDeleteResult deleteOne(String provider, String sessionId) {
        if (!HistoryDeleteService.isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return HistoryDeleteResult.none();
        }
        HistoryDeleteResult result = deleteWithoutRefresh(provider, sessionId);
        clearCache(provider);
        refresh(provider);
        return result;
    }

    HistoryBatchDeleteResult deleteMany(String provider, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch delete failed: empty sessionIds");
            return new HistoryBatchDeleteResult(0, 0, 0);
        }

        int mainDeletedCount = 0;
        int agentFilesDeletedCount = 0;
        for (String sessionId : sessionIds) {
            try {
                HistoryDeleteResult result = deleteWithoutRefresh(provider, sessionId);
                if (result.mainDeleted()) {
                    mainDeletedCount++;
                }
                agentFilesDeletedCount += result.agentFilesDeleted();
            } catch (Exception e) {
                LOG.error("[HistoryHandler] Batch delete single session failed: " + sessionId + " - " + e.getMessage(), e);
            }
        }

        clearCache(provider);
        LOG.info("[HistoryHandler] Batch delete completed - Main files: " + mainDeletedCount + "/" + sessionIds.size()
                + ", Agent files: " + agentFilesDeletedCount);
        refresh(provider);
        return new HistoryBatchDeleteResult(sessionIds.size(), mainDeletedCount, agentFilesDeletedCount);
    }

    HistoryDeleteResult deleteWithoutRefresh(String provider, String sessionId) {
        if (!HistoryDeleteService.isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return HistoryDeleteResult.none();
        }

        try {
            String projectPath = resolveProjectPath();
            HistoryProviderAdapter adapter = providerRegistry.adapter(provider);
            if (!adapter.supports(HistoryCapability.DELETE)) {
                LOG.warn("[HistoryHandler] Delete session rejected: provider does not support deletion: "
                        + adapter.provider().value());
                return HistoryDeleteResult.none();
            }
            HistoryDeleteResult result = adapter.deleteSession(sessionId, projectPath);
            if (result.mainDeleted()) {
                cleanupSessionMetadata(sessionId);
            }
            cleanupSessionAttachments(adapter.provider().value(), sessionId);
            LOG.info("[HistoryHandler] Delete completed - Main file: "
                    + (result.mainDeleted() ? "deleted" : "not found")
                    + ", Agent files: " + result.agentFilesDeleted());
            return result;
        } catch (IOException e) {
            LOG.error("[HistoryHandler] Delete session failed: " + e.getMessage(), e);
            return HistoryDeleteResult.none();
        } catch (Exception e) {
            LOG.error("[HistoryHandler] Delete session failed: " + e.getMessage(), e);
            return HistoryDeleteResult.none();
        }
    }


    HistoryBatchArchiveResult archiveMany(String provider, List<String> sessionIds) {
        List<String> requestedSessionIds = sessionIds == null
                ? List.of()
                : sessionIds.stream().distinct().toList();
        if (requestedSessionIds.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch archive failed: empty sessionIds");
            return HistoryBatchArchiveResult.none(requestedSessionIds);
        }

        try {
            String projectPath = resolveProjectPath();
            HistoryProviderAdapter adapter = providerRegistry.adapter(provider);
            if (!adapter.supports(HistoryCapability.ARCHIVE)) {
                LOG.warn("[HistoryHandler] Archive sessions rejected: provider does not support archive: "
                        + adapter.provider().value());
                return HistoryBatchArchiveResult.none(requestedSessionIds);
            }

            List<String> archivedSessionIds = new java.util.ArrayList<>();
            for (String sessionId : requestedSessionIds) {
                if (!HistoryDeleteService.isValidSessionId(sessionId)) {
                    LOG.warn("[HistoryHandler] Archive session rejected: invalid sessionId");
                    continue;
                }
                try {
                    HistoryArchiveResult result = adapter.archiveSession(sessionId, projectPath);
                    if (result.archived()) {
                        archivedSessionIds.add(sessionId);
                    }
                } catch (Exception e) {
                    LOG.error("[HistoryHandler] Archive single session failed: " + sessionId
                            + " - " + e.getMessage(), e);
                }
            }

            clearCache(provider);
            refresh(provider);
            return new HistoryBatchArchiveResult(requestedSessionIds, archivedSessionIds);
        } catch (Exception e) {
            LOG.error("[HistoryHandler] Archive sessions failed: " + e.getMessage(), e);
            return HistoryBatchArchiveResult.none(requestedSessionIds);
        }
    }

    void clearCache(String provider) {
        try {
            String projectPath = resolveProjectPath();
            providerRegistry.adapter(provider).clearCache(projectPath);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up cache (does not affect deletion): " + e.getMessage());
        }
    }

    private String resolveProjectPath() {
        if (projectPathSupplier != null) {
            return projectPathSupplier.get();
        }
        return projectPathResolver.resolve(context);
    }

    private void cleanupSessionMetadata(String sessionId) {
        if (metadataCleanup != null) {
            metadataCleanup.accept(sessionId);
            return;
        }
        try {
            nodeJsServiceCaller.callNodeJsFavoritesService("removeFavorite", sessionId);
            nodeJsServiceCaller.callNodeJsDeleteTitle(sessionId);
            LOG.info("[HistoryHandler] Cleaned up session metadata");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up metadata (does not affect deletion): " + e.getMessage());
        }
    }

    private void cleanupSessionAttachments(String provider, String sessionId) {
        if (attachmentCleanup != null) {
            attachmentCleanup.accept(provider, sessionId);
            return;
        }
        try {
            AttachmentStorageService storageService = AttachmentStorageService.getInstance();
            storageService.deleteSessionRecords(provider, sessionId);
            storageService.cleanupOrphanedResources(storageService.collectAllReferencedHashes());
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up session attachments: " + e.getMessage());
        }
    }
}


