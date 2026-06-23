package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;

/**
 * Container for provider action handlers (B2 迁移).
 *
 * <p>Assembles the four provider operation collaborators and exposes one entry
 * point per protocol action. The per-action {@code FrontendActionHandler} adapters
 * delegate here, preserving the original Claude/Codex/import-export/ordering split.
 */
public class ProviderActionHandlers {

    private final ClaudeProviderOperations claudeOps;
    private final CodexProviderOperations codexOps;
    private final ProviderImportExportSupport importExportSupport;
    private final ProviderOrderingService orderingService;

    public ProviderActionHandlers(HandlerContext context) {
        this.claudeOps = new ClaudeProviderOperations(context);
        this.codexOps = new CodexProviderOperations(context);
        this.importExportSupport = new ProviderImportExportSupport(context, claudeOps);
        this.orderingService = new ProviderOrderingService(context, claudeOps, codexOps);
    }

    // ============================================================================
    // Claude provider operations
    // ============================================================================

    public void handleGetProviders() {
        claudeOps.handleGetProviders();
    }

    public void handleGetCurrentClaudeConfig() {
        claudeOps.handleGetCurrentClaudeConfig();
    }

    public void handleGetThinkingEnabled() {
        claudeOps.handleGetThinkingEnabled();
    }

    public void handleSetThinkingEnabled(String content) {
        claudeOps.handleSetThinkingEnabled(content);
    }

    public void handleAddProvider(String content) {
        claudeOps.handleAddProvider(content);
    }

    public void handleUpdateProvider(String content) {
        claudeOps.handleUpdateProvider(content);
    }

    public void handleDeleteProvider(String content) {
        claudeOps.handleDeleteProvider(content);
    }

    public void handleSwitchProvider(String content) {
        claudeOps.handleSwitchProvider(content);
    }

    public void handleGetActiveProvider() {
        claudeOps.handleGetActiveProvider();
    }

    public void handlePreviewCcSwitchImport() {
        importExportSupport.handlePreviewCcSwitchImport();
    }

    public void handleOpenFileChooserForCcSwitch() {
        importExportSupport.handleOpenFileChooserForCcSwitch();
    }

    public void handleSaveImportedProviders(String content) {
        importExportSupport.handleSaveImportedProviders(content);
    }

    public void handleSortProviders(String content) {
        orderingService.handleSortProviders(content);
    }

    // ============================================================================
    // Codex provider operations
    // ============================================================================

    public void handleGetCodexProviders() {
        codexOps.handleGetCodexProviders();
    }

    public void handleGetCurrentCodexConfig() {
        codexOps.handleGetCurrentCodexConfig();
    }

    public void handleAddCodexProvider(String content) {
        codexOps.handleAddCodexProvider(content);
    }

    public void handleUpdateCodexProvider(String content) {
        codexOps.handleUpdateCodexProvider(content);
    }

    public void handleDeleteCodexProvider(String content) {
        codexOps.handleDeleteCodexProvider(content);
    }

    public void handleSwitchCodexProvider(String content) {
        codexOps.handleSwitchCodexProvider(content);
    }

    public void handleRevokeCodexLocalConfigAuthorization(String content) {
        codexOps.handleRevokeCodexLocalConfigAuthorization(content);
    }

    public void handleGetActiveCodexProvider() {
        codexOps.handleGetActiveCodexProvider();
    }

    public void handleSortCodexProviders(String content) {
        orderingService.handleSortCodexProviders(content);
    }
}
