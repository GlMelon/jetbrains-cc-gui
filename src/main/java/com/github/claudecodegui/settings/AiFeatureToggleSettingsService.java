package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * AI 功能开关与 Smithery 凭证领域 Service。
 *
 * <p>普通开关持久化到 {@link ConfigStore}；Smithery API key 以 {@link com.github.claudecodegui.settings.credentials.PasswordStore}
 * 为权威存储，仅在安全后端暂不可用且迁移被延迟时兼容读取旧明文字段。Facade 只保留公共调用面。
 */
public final class AiFeatureToggleSettingsService {
    private static final Logger LOG = Logger.getInstance(AiFeatureToggleSettingsService.class);

    private final ConfigStore configStore;
    private final PasswordStore passwordStore;

    // ==================== Field keys (promoted from CSS inline literals) ====================

    private static final String COMMIT_GENERATION_KEY = "commitGenerationEnabled";
    private static final String MCP_GATEWAY_KEY = "mcpGatewayEnabled";
    private static final String STATUS_BAR_WIDGET_KEY = "statusBarWidgetEnabled";
    private static final String AI_TITLE_GENERATION_KEY = "aiTitleGenerationEnabled";

    public AiFeatureToggleSettingsService(ConfigStore configStore, PasswordStore passwordStore) {
        this.configStore = configStore;
        this.passwordStore = passwordStore;
    }

    // ==================== Commit generation ====================

    /** Get whether AI commit message generation is enabled (default true). */
    public boolean getCommitGenerationEnabled() throws IOException {
        JsonObject config = configStore.read();
        if (config.has(COMMIT_GENERATION_KEY) && !config.get(COMMIT_GENERATION_KEY).isJsonNull()) {
            return config.get(COMMIT_GENERATION_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether AI commit message generation is enabled. */
    public void setCommitGenerationEnabled(boolean enabled) throws IOException {
        configStore.update(config -> config.addProperty(COMMIT_GENERATION_KEY, enabled));
        LOG.info("[AiFeatureToggle] Set commit generation enabled: " + enabled);
    }

    // ==================== MCP gateway ====================

    /** Get whether MCP Gateway acceleration (resident MCP prewarm) is user-enabled (default true). */
    public boolean getMcpGatewayEnabled() throws IOException {
        JsonObject config = configStore.read();
        if (config.has(MCP_GATEWAY_KEY) && !config.get(MCP_GATEWAY_KEY).isJsonNull()) {
            return config.get(MCP_GATEWAY_KEY).getAsBoolean();
        }
        return true;
    }

    /**
     * Set whether MCP Gateway acceleration is user-enabled. Closing this falls back to
     * direct MCP connections (slower first request); the gateway Node process is stopped.
     */
    public void setMcpGatewayEnabled(boolean enabled) throws IOException {
        configStore.update(config -> config.addProperty(MCP_GATEWAY_KEY, enabled));
        LOG.info("[AiFeatureToggle] Set MCP gateway enabled: " + enabled);
    }

    // ==================== Smithery API key ====================

    /** Get the Smithery Registry API key (used by MCP market to search/fetch server configs), or empty string. */
    public String getSmitheryApiKey() throws IOException {
        JsonObject config = configStore.read();
        String secret = passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY);
        if (secret != null) {
            return secret;
        }
        if (config.has(ConfigSchema.SMITHERY_API_KEY)
                && !config.get(ConfigSchema.SMITHERY_API_KEY).isJsonNull()) {
            return config.get(ConfigSchema.SMITHERY_API_KEY).getAsString();
        }
        return "";
    }

    /**
     * Set the Smithery Registry API key. Empty/null clears it.
     * <p>Security: the key value itself is never logged — only the set/cleared state is logged.
     * Non-empty values are stored securely before legacy plaintext cleanup; clearing removes the
     * plaintext fallback before deleting the secure value, so a failed config update cannot revive it.
     */
    public void setSmitheryApiKey(String apiKey) throws IOException {
        boolean cleared = apiKey == null || apiKey.isEmpty();
        if (!cleared) {
            passwordStore.storePassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY, apiKey);
        }
        configStore.update(config -> config.remove(ConfigSchema.SMITHERY_API_KEY));
        if (cleared) {
            passwordStore.removePassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY);
        }
        LOG.info("[AiFeatureToggle] Smithery API key " + (cleared ? "cleared" : "updated"));
    }

    // ==================== Status bar widget ====================

    /** Get whether status bar widget is enabled (default true). */
    public boolean getStatusBarWidgetEnabled() throws IOException {
        JsonObject config = configStore.read();
        if (config.has(STATUS_BAR_WIDGET_KEY) && !config.get(STATUS_BAR_WIDGET_KEY).isJsonNull()) {
            return config.get(STATUS_BAR_WIDGET_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether status bar widget is enabled. */
    public void setStatusBarWidgetEnabled(boolean enabled) throws IOException {
        configStore.update(config -> config.addProperty(STATUS_BAR_WIDGET_KEY, enabled));
        LOG.info("[AiFeatureToggle] Set status bar widget enabled: " + enabled);
    }

    // ==================== AI title generation ====================

    /** Get whether AI session title generation is enabled (default true). */
    public boolean getAiTitleGenerationEnabled() throws IOException {
        JsonObject config = configStore.read();
        if (config.has(AI_TITLE_GENERATION_KEY) && !config.get(AI_TITLE_GENERATION_KEY).isJsonNull()) {
            return config.get(AI_TITLE_GENERATION_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether AI session title generation is enabled. */
    public void setAiTitleGenerationEnabled(boolean enabled) throws IOException {
        configStore.update(config -> config.addProperty(AI_TITLE_GENERATION_KEY, enabled));
        LOG.info("[AiFeatureToggle] Set AI title generation enabled: " + enabled);
    }
}
