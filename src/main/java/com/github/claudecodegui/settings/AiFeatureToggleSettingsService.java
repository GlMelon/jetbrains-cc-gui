package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * AI Feature Toggle 领域 Service(A3 领域拆分第二步,docs §A3)。
 *
 * <p>封装 5 对 AI 功能开关的读写:4 个 boolean toggle(commit 生成 / MCP gateway / 状态栏 widget /
 * AI 标题生成,均默认 true)+ Smithery Registry API key(set null/empty → remove 的安全清理,
 * 值本身不入日志,文件落盘 0600)。
 *
 * <p>与 {@link AppearanceSettingsService} 同为「模式 A 半拆」:构造注入 {@link CodemossSettingsService},
 * 持久化走 {@code css.readConfig()/writeConfig()}。核心理由同第一步 —— 文件缺失时
 * {@code CSS.readConfig()} 返回 {@code createDefaultConfig()} 全局骨架,Service 在其上读/写单个字段,
 * 行为与历史逐字等价;直连 {@link ConfigRepository} 会丢失全局默认段。
 *
 * <p><b>零核心路径耦合</b>:5 对方法都是机械的 {@code readConfig + has/isJsonNull 校验 + writeConfig}
 * 三件套,不触 Provider Management / Model Registry / MCP Server Management;{@code getSmitheryApiKey}
 * 被 MCP market 当 bearer 字符串消费、{@code getMcpGatewayEnabled} 被 FeatureFlags 当 boolean 开关消费,
 * 均为值消费,不反向调用核心路径。爆炸半径 = 0。
 *
 * <p><b>Facade 不变</b>:CSS 10 个 public 签名保留为单行转发委托;调用面与既有测试零改动。
 */
public final class AiFeatureToggleSettingsService {
    private static final Logger LOG = Logger.getInstance(AiFeatureToggleSettingsService.class);

    private final CodemossSettingsService settingsService;

    // ==================== Field keys (promoted from CSS inline literals) ====================

    private static final String COMMIT_GENERATION_KEY = "commitGenerationEnabled";
    private static final String MCP_GATEWAY_KEY = "mcpGatewayEnabled";
    private static final String SMITHERY_API_KEY_KEY = "smitheryApiKey";
    private static final String STATUS_BAR_WIDGET_KEY = "statusBarWidgetEnabled";
    private static final String AI_TITLE_GENERATION_KEY = "aiTitleGenerationEnabled";

    public AiFeatureToggleSettingsService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ==================== Commit generation ====================

    /** Get whether AI commit message generation is enabled (default true). */
    public boolean getCommitGenerationEnabled() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(COMMIT_GENERATION_KEY) && !config.get(COMMIT_GENERATION_KEY).isJsonNull()) {
            return config.get(COMMIT_GENERATION_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether AI commit message generation is enabled. */
    public void setCommitGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = settingsService.readConfig();
        config.addProperty(COMMIT_GENERATION_KEY, enabled);
        settingsService.writeConfig(config);
        LOG.info("[AiFeatureToggle] Set commit generation enabled: " + enabled);
    }

    // ==================== MCP gateway ====================

    /** Get whether MCP Gateway acceleration (resident MCP prewarm) is user-enabled (default true). */
    public boolean getMcpGatewayEnabled() throws IOException {
        JsonObject config = settingsService.readConfig();
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
        JsonObject config = settingsService.readConfig();
        config.addProperty(MCP_GATEWAY_KEY, enabled);
        settingsService.writeConfig(config);
        LOG.info("[AiFeatureToggle] Set MCP gateway enabled: " + enabled);
    }

    // ==================== Smithery API key ====================

    /** Get the Smithery Registry API key (used by MCP market to search/fetch server configs), or empty string. */
    public String getSmitheryApiKey() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(SMITHERY_API_KEY_KEY) && !config.get(SMITHERY_API_KEY_KEY).isJsonNull()) {
            return config.get(SMITHERY_API_KEY_KEY).getAsString();
        }
        return "";
    }

    /**
     * Set the Smithery Registry API key. Empty/null clears it.
     * <p>Security: the key value itself is never logged — only the set/cleared state
     * is logged. {@code writeConfig} hardens the file to {@code 0600}.
     */
    public void setSmitheryApiKey(String apiKey) throws IOException {
        JsonObject config = settingsService.readConfig();
        if (apiKey == null || apiKey.isEmpty()) {
            config.remove(SMITHERY_API_KEY_KEY);
        } else {
            config.addProperty(SMITHERY_API_KEY_KEY, apiKey);
        }
        settingsService.writeConfig(config);
        boolean cleared = apiKey == null || apiKey.isEmpty();
        LOG.info("[AiFeatureToggle] Smithery API key " + (cleared ? "cleared" : "updated"));
    }

    // ==================== Status bar widget ====================

    /** Get whether status bar widget is enabled (default true). */
    public boolean getStatusBarWidgetEnabled() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(STATUS_BAR_WIDGET_KEY) && !config.get(STATUS_BAR_WIDGET_KEY).isJsonNull()) {
            return config.get(STATUS_BAR_WIDGET_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether status bar widget is enabled. */
    public void setStatusBarWidgetEnabled(boolean enabled) throws IOException {
        JsonObject config = settingsService.readConfig();
        config.addProperty(STATUS_BAR_WIDGET_KEY, enabled);
        settingsService.writeConfig(config);
        LOG.info("[AiFeatureToggle] Set status bar widget enabled: " + enabled);
    }

    // ==================== AI title generation ====================

    /** Get whether AI session title generation is enabled (default true). */
    public boolean getAiTitleGenerationEnabled() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(AI_TITLE_GENERATION_KEY) && !config.get(AI_TITLE_GENERATION_KEY).isJsonNull()) {
            return config.get(AI_TITLE_GENERATION_KEY).getAsBoolean();
        }
        return true;
    }

    /** Set whether AI session title generation is enabled. */
    public void setAiTitleGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = settingsService.readConfig();
        config.addProperty(AI_TITLE_GENERATION_KEY, enabled);
        settingsService.writeConfig(config);
        LOG.info("[AiFeatureToggle] Set AI title generation enabled: " + enabled);
    }
}
