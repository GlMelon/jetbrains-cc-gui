package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Container for history action handlers (B2/B4 迁移).
 *
 * <p>Assembles the seven history service collaborators and exposes one entry
 * point per protocol action. Holds the shared mutable {@code currentProvider}
 * state and the {@link SessionLoadCallback} previously wired via the legacy
 * {@code HistoryHandler}.
 */
public class HistoryActionHandlers {

    private static final Logger LOG = Logger.getInstance(HistoryActionHandlers.class);

    // Session load callback interface
    public interface SessionLoadCallback {
        void onLoadSession(String sessionId, String projectPath, String provider);
    }

    private SessionLoadCallback sessionLoadCallback;
    private String currentProvider = CommonConstants.PROVIDER_CLAUDE; // Default to claude

    private final HandlerContext context;
    private final HistoryWorkflowService historyWorkflowService;
    private final HistoryExportService historyExportService;
    private final HistoryMessageInjector historyMessageInjector;
    private final HistoryMetadataService historyMetadataService;
    private final SubagentHistoryService subagentHistoryService;
    private final SessionConversionService sessionConversionService;

    public HistoryActionHandlers(HandlerContext context) {
        this.context = context;
        NodeJsServiceCaller nodeJsServiceCaller = new NodeJsServiceCaller(context);
        HistoryProviderRegistry historyProviderRegistry = HistoryProviderRegistry.createDefault(context);
        HistoryLoadService historyLoadService = new HistoryLoadService(context);
        HistoryProjectPathResolver projectPathResolver = new HistoryProjectPathResolver();
        HistorySessionsJsonEnhancer sessionsJsonEnhancer = new HistorySessionsJsonEnhancer(nodeJsServiceCaller);
        this.historyWorkflowService = new HistoryWorkflowService(context, historyProviderRegistry, projectPathResolver,
                sessionsJsonEnhancer, historyLoadService, nodeJsServiceCaller);
        this.historyExportService = new HistoryExportService(context, historyProviderRegistry);
        this.historyMessageInjector = new HistoryMessageInjector(context);
        this.historyMetadataService = new HistoryMetadataService(context, nodeJsServiceCaller);
        this.subagentHistoryService = new SubagentHistoryService(context);
        this.sessionConversionService = new SessionConversionService(context);
    }

    public void setSessionLoadCallback(SessionLoadCallback callback) {
        this.sessionLoadCallback = callback;
    }

    // ============================================================================
    // Operations
    // ============================================================================

    public void handleLoadHistoryData(String content) {
        LOG.debug("[HistoryHandler] 处理: load_history_data, provider=" + content);
        this.currentProvider = content != null && !content.isEmpty() ? content : CommonConstants.PROVIDER_CLAUDE;
        String providerSnapshot = this.currentProvider;
        CompletableFuture.runAsync(() -> historyWorkflowService.refresh(providerSnapshot));
    }

    public void handleLoadSession(String content) {
        LOG.debug("[HistoryHandler] 处理: load_session");
        historyMessageInjector.handleLoadSession(content, currentProvider, sessionLoadCallback);
    }

    public void handleDeleteSession(String content) {
        LOG.info("[HistoryHandler] 处理: delete_session, sessionId=" + content);
        String providerSnapshot = this.currentProvider;
        CompletableFuture.runAsync(() -> historyWorkflowService.deleteOne(providerSnapshot, content));
    }

    public void handleDeleteSessions(String content) {
        LOG.info("[HistoryHandler] 处理: delete_sessions");
        List<String> sessionIds = HistoryDeleteService.parseSessionIds(content);
        if (sessionIds.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch delete failed: empty sessionIds");
            return;
        }
        String providerSnapshot = this.currentProvider;
        LOG.info("[HistoryHandler] Batch delete sessionIds: " + HistoryDeleteService.sessionIdsToJson(sessionIds));
        CompletableFuture.runAsync(() -> historyWorkflowService.deleteMany(providerSnapshot, sessionIds));
    }

    public void handleExportSession(String content) {
        LOG.info("[HistoryHandler] 处理: export_session, sessionId=" + content);
        historyExportService.handleExportSession(content, currentProvider);
    }

    public void handleToggleFavorite(String content) {
        LOG.info("[HistoryHandler] 处理: toggle_favorite, sessionId=" + content);
        historyMetadataService.handleToggleFavorite(content);
    }

    public void handleUpdateTitle(String content) {
        LOG.info("[HistoryHandler] 处理: update_title");
        historyMetadataService.handleUpdateTitle(content);
    }

    public void handleDeleteTitle(String content) {
        LOG.info("[HistoryHandler] 处理: delete_title, sessionId=" + content);
        historyMetadataService.handleDeleteTitle(content);
    }

    public void handleDeepSearchHistory(String content) {
        LOG.info("[HistoryHandler] 处理: deep_search_history, provider=" + content);
        this.currentProvider = content != null && !content.isEmpty() ? content : CommonConstants.PROVIDER_CLAUDE;
        String providerSnapshot = this.currentProvider;
        CompletableFuture.runAsync(() -> {
            historyWorkflowService.clearCache(providerSnapshot);
            historyWorkflowService.refresh(providerSnapshot);
        });
    }

    public void handleLoadSubagentSession(String content) {
        LOG.debug("[HistoryHandler] 处理: load_subagent_session");
        subagentHistoryService.handleLoadSubagentSession(content);
    }

    public void handleConvertToCliSession(String content) {
        LOG.debug("[HistoryHandler] 处理: convert_to_cli_session");
        String conversionProjectPath = this.context.getProject() != null
                ? this.context.getProject().getBasePath() : null;
        this.sessionConversionService.convertSdkSession(content, conversionProjectPath);
    }
}

