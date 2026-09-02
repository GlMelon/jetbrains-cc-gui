package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.OpenCodeProviderManager;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * Handles OpenCode provider CRUD operations and switching.
 *
 * <p>对称 {@link CodexProviderOperations}(Principle 6 多 provider 对称):
 * <ul>
 *   <li>CRUD/switch/local-config 走 SSOT opencode 段 + 外科手术式合并 opencode.json。</li>
 *   <li>无订阅配额缓存(opencode 无该概念)。</li>
 *   <li>provider 变更后下发 {@link DownstreamEvent#MODEL_REGISTRY} —— 因管理型 provider 经合并写入
 *       opencode.json,现有 {@code OpenCodeConfigReader.readModels()} 自动读回其模型,故刷新 registry 即生效。</li>
 * </ul>
 */
public class OpenCodeProviderOperations {

    private static final Logger LOG = Logger.getInstance(OpenCodeProviderOperations.class);
    private static final Gson GSON = GsonHolder.GSON;

    private final HandlerContext context;

    public OpenCodeProviderOperations(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get all OpenCode providers
     */
    public void handleGetOpenCodeProviders() {
        try {
            List<JsonObject> providers = context.getSettingsService().getOpenCodeProviders();
            String providersJson = GSON.toJson(providers);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.PROVIDER_OPENCODE_LIST.value(), providersJson);
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get OpenCode providers: " + e.getMessage(), e);
        }
    }

    /**
     * Get current OpenCode native configuration (opencode.json, local-config mode)
     */
    public void handleGetCurrentOpenCodeConfig() {
        try {
            JsonObject config = context.getSettingsService().getCurrentOpenCodeConfig();
            String configJson = GSON.toJson(config);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.PROVIDER_OPENCODE_CONFIG.value(), configJson);
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get current OpenCode config: " + e.getMessage(), e);
        }
    }

    /**
     * Add OpenCode provider
     */
    public void handleAddOpenCodeProvider(String content) {
        try {
            JsonObject provider = GSON.fromJson(content, JsonObject.class);
            context.getSettingsService().addOpenCodeProvider(provider);

            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetOpenCodeProviders(); // Refresh list
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to add OpenCode provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(),
                        ClaudeCodeGuiBundle.message("provider.addOpenCodeFailed", e.getMessage()));
            });
        }
    }

    /**
     * Update OpenCode provider.活跃 provider 被更新时同步刷新 opencode.json + registry。
     */
    public void handleUpdateOpenCodeProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");

            context.getSettingsService().updateOpenCodeProvider(id, updates);

            boolean syncedActiveProvider = false;
            JsonObject activeProvider = context.getSettingsService().getActiveOpenCodeProvider();
            if (activeProvider != null &&
                        activeProvider.has("id") &&
                        id.equals(activeProvider.get("id").getAsString())) {
                context.getSettingsService().applyActiveProviderToOpenCodeSettings();
                syncedActiveProvider = true;
            }

            final boolean finalSynced = syncedActiveProvider;
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetOpenCodeProviders(); // Refresh list
                if (finalSynced) {
                    handleGetActiveOpenCodeProvider();
                    context.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                            context.getSettingsService().getModelRegistryJson());
                }
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to update OpenCode provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(),
                        ClaudeCodeGuiBundle.message("provider.updateOpenCodeFailed", e.getMessage()));
            });
        }
    }

    /**
     * Delete OpenCode provider
     */
    public void handleDeleteOpenCodeProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);

            if (!data.has("id")) {
                LOG.error("[ProviderHandler] ERROR: Missing 'id' field in request");
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(),
                            ClaudeCodeGuiBundle.message("provider.deleteOpenCodeMissingId"));
                });
                return;
            }

            String id = data.get("id").getAsString();
            LOG.info("[ProviderHandler] Deleting OpenCode provider with ID: " + id);

            DeleteResult result = context.getSettingsService().deleteOpenCodeProvider(id);

            if (result.isSuccess()) {
                LOG.info("[ProviderHandler] Delete successful, refreshing provider list");
                ApplicationManager.getApplication().invokeLater(() -> {
                    handleGetOpenCodeProviders(); // Refresh list
                    handleGetActiveOpenCodeProvider();
                    context.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                            context.getSettingsService().getModelRegistryJson());
                });
            } else {
                String errorMsg = result.getUserFriendlyMessage();
                LOG.warn("[ProviderHandler] Delete OpenCode provider failed: " + errorMsg);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
                });
            }
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Exception in handleDeleteOpenCodeProvider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(),
                        ClaudeCodeGuiBundle.message("provider.deleteOpenCodeFailed", e.getMessage()));
            });
        }
    }

    /**
     * Switch OpenCode provider.本地配置虚拟 provider 走授权分支;管理型 provider 切换后合并刷新 + registry。
     */
    public void handleSwitchOpenCodeProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();

            // 本地配置虚拟 provider —— 授权分支
            if (OpenCodeProviderManager.OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(id)) {
                handleSwitchToOpenCodeLocalConfig();
                return;
            }

            context.getSettingsService().switchOpenCodeProvider(id);
            context.getSettingsService().applyActiveProviderToOpenCodeSettings();

            ApplicationManager.getApplication().invokeLater(() -> {
                String successMsg = ClaudeCodeGuiBundle.message("toast.providerSwitchSuccess")
                        + ClaudeCodeGuiBundle.message("provider.switchSyncOpenCode");
                context.dispatchEvent(DownstreamEvent.TOAST_SWITCH_SUCCESS.value(), successMsg);
                handleGetOpenCodeProviders();
                handleGetCurrentOpenCodeConfig();
                handleGetActiveOpenCodeProvider();
                context.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                        context.getSettingsService().getModelRegistryJson());
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch OpenCode provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(),
                        ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage());
            });
        }
    }

    /**
     * Revoke local OpenCode config authorization and stop treating opencode.json as authoritative read-only.
     */
    public void handleRevokeOpenCodeLocalConfigAuthorization(String content) {
        try {
            JsonObject data = content == null || content.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(content, JsonObject.class);
            String fallbackProviderId = data != null && data.has("fallbackProviderId")
                    && !data.get("fallbackProviderId").isJsonNull()
                    ? data.get("fallbackProviderId").getAsString()
                    : "";

            JsonObject activeProvider = context.getSettingsService().getActiveOpenCodeProvider();
            boolean wasLocalConfigActive = activeProvider != null
                    && activeProvider.has("id")
                    && OpenCodeProviderManager.OPENCODE_LOCAL_CONFIG_PROVIDER_ID
                            .equals(activeProvider.get("id").getAsString());

            context.getSettingsService().setOpencodeLocalConfigAuthorized(false);

            if (wasLocalConfigActive) {
                context.getSettingsService().switchOpenCodeProvider(fallbackProviderId);
                if (fallbackProviderId != null && !fallbackProviderId.isBlank()) {
                    context.getSettingsService().applyActiveProviderToOpenCodeSettings();
                }
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_SWITCH_SUCCESS.value(), 
                        ClaudeCodeGuiBundle.message("toast.opencodeLocalConfigAuthorizationRevoked"));
                handleGetOpenCodeProviders();
                handleGetCurrentOpenCodeConfig();
                handleGetActiveOpenCodeProvider();
                context.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                        context.getSettingsService().getModelRegistryJson());
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to revoke OpenCode local config authorization: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), 
                        ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage());
            });
        }
    }

    /**
     * 授权本地配置模式:以 opencode.json 为权威(只读),不合并写入。
     */
    private void handleSwitchToOpenCodeLocalConfig() {
        try {
            context.getSettingsService().setOpencodeLocalConfigAuthorized(true);
            context.getSettingsService().switchOpenCodeProvider(
                    OpenCodeProviderManager.OPENCODE_LOCAL_CONFIG_PROVIDER_ID);

            LOG.info("[ProviderHandler] Authorized local OpenCode config provider");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_SWITCH_SUCCESS.value(), 
                        ClaudeCodeGuiBundle.message("toast.opencodeLocalConfigSwitchSuccess"));
                handleGetOpenCodeProviders();
                handleGetCurrentOpenCodeConfig();
                handleGetActiveOpenCodeProvider();
                context.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(),
                        context.getSettingsService().getModelRegistryJson());
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch to OpenCode local config: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), 
                        ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage());
            });
        }
    }

    /**
     * Get currently active OpenCode provider
     */
    public void handleGetActiveOpenCodeProvider() {
        try {
            JsonObject provider = context.getSettingsService().getActiveOpenCodeProvider();
            String providerJson = GSON.toJson(provider);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.PROVIDER_ACTIVE_OPENCODE.value(), providerJson);
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get active OpenCode provider: " + e.getMessage(), e);
        }
    }
}
