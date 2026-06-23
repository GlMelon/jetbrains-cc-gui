package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for provider action handlers (B2 迁移, ProviderHandler).
 * Verifies action/payloadType contract only — business logic lives in the four
 * provider operation collaborators reached via ProviderActionHandlers.
 */
public class ProviderActionHandlerContractTest {

    private void assertContract(FrontendActionHandler<String> h, UpstreamAction expected, String value) {
        Assert.assertEquals(expected, h.action());
        Assert.assertEquals(value, h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    // ---- Claude ----

    @Test public void testGetProviders() {
        assertContract(new GetProvidersActionHandler(null), UpstreamAction.GET_PROVIDERS, "get_providers");
    }

    @Test public void testGetCurrentClaudeConfig() {
        assertContract(new GetCurrentClaudeConfigActionHandler(null), UpstreamAction.GET_CURRENT_CLAUDE_CONFIG, "get_current_claude_config");
    }

    @Test public void testGetThinkingEnabled() {
        assertContract(new GetThinkingEnabledActionHandler(null), UpstreamAction.GET_THINKING_ENABLED, "get_thinking_enabled");
    }

    @Test public void testSetThinkingEnabled() {
        assertContract(new SetThinkingEnabledActionHandler(null), UpstreamAction.SET_THINKING_ENABLED, "set_thinking_enabled");
    }

    @Test public void testAddProvider() {
        assertContract(new AddProviderActionHandler(null), UpstreamAction.ADD_PROVIDER, "add_provider");
    }

    @Test public void testUpdateProvider() {
        assertContract(new UpdateProviderActionHandler(null), UpstreamAction.UPDATE_PROVIDER, "update_provider");
    }

    @Test public void testDeleteProvider() {
        assertContract(new DeleteProviderActionHandler(null), UpstreamAction.DELETE_PROVIDER, "delete_provider");
    }

    @Test public void testSwitchProvider() {
        assertContract(new SwitchProviderActionHandler(null), UpstreamAction.SWITCH_PROVIDER, "switch_provider");
    }

    @Test public void testGetActiveProvider() {
        assertContract(new GetActiveProviderActionHandler(null), UpstreamAction.GET_ACTIVE_PROVIDER, "get_active_provider");
    }

    @Test public void testPreviewCcSwitchImport() {
        assertContract(new PreviewCcSwitchImportActionHandler(null), UpstreamAction.PREVIEW_CC_SWITCH_IMPORT, "preview_cc_switch_import");
    }

    @Test public void testOpenFileChooserForCcSwitch() {
        assertContract(new OpenFileChooserForCcSwitchActionHandler(null), UpstreamAction.OPEN_FILE_CHOOSER_FOR_CC_SWITCH, "open_file_chooser_for_cc_switch");
    }

    @Test public void testSaveImportedProviders() {
        assertContract(new SaveImportedProvidersActionHandler(null), UpstreamAction.SAVE_IMPORTED_PROVIDERS, "save_imported_providers");
    }

    @Test public void testSortProviders() {
        assertContract(new SortProvidersActionHandler(null), UpstreamAction.SORT_PROVIDERS, "sort_providers");
    }

    // ---- Codex ----

    @Test public void testGetCodexProviders() {
        assertContract(new GetCodexProvidersActionHandler(null), UpstreamAction.GET_CODEX_PROVIDERS, "get_codex_providers");
    }

    @Test public void testGetCurrentCodexConfig() {
        assertContract(new GetCurrentCodexConfigActionHandler(null), UpstreamAction.GET_CURRENT_CODEX_CONFIG, "get_current_codex_config");
    }

    @Test public void testAddCodexProvider() {
        assertContract(new AddCodexProviderActionHandler(null), UpstreamAction.ADD_CODEX_PROVIDER, "add_codex_provider");
    }

    @Test public void testUpdateCodexProvider() {
        assertContract(new UpdateCodexProviderActionHandler(null), UpstreamAction.UPDATE_CODEX_PROVIDER, "update_codex_provider");
    }

    @Test public void testDeleteCodexProvider() {
        assertContract(new DeleteCodexProviderActionHandler(null), UpstreamAction.DELETE_CODEX_PROVIDER, "delete_codex_provider");
    }

    @Test public void testSwitchCodexProvider() {
        assertContract(new SwitchCodexProviderActionHandler(null), UpstreamAction.SWITCH_CODEX_PROVIDER, "switch_codex_provider");
    }

    @Test public void testRevokeCodexLocalConfigAuthorization() {
        assertContract(new RevokeCodexLocalConfigAuthorizationActionHandler(null), UpstreamAction.REVOKE_CODEX_LOCAL_CONFIG_AUTHORIZATION, "revoke_codex_local_config_authorization");
    }

    @Test public void testGetActiveCodexProvider() {
        assertContract(new GetActiveCodexProviderActionHandler(null), UpstreamAction.GET_ACTIVE_CODEX_PROVIDER, "get_active_codex_provider");
    }

    @Test public void testSortCodexProviders() {
        assertContract(new SortCodexProvidersActionHandler(null), UpstreamAction.SORT_CODEX_PROVIDERS, "sort_codex_providers");
    }

    @Test public void testImplementsFrontendActionHandler() {
        Assert.assertTrue(new GetProvidersActionHandler(null) instanceof FrontendActionHandler<?>);
    }
}
