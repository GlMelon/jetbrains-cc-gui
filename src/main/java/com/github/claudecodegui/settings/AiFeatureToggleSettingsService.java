package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * AI Feature Toggle 领域 Service(A3 领域拆分第二步,docs §A3)。
 *
 * <p>封装 5 对 AI 功能开关的读写:4 个 boolean toggle(commit 生成 / MCP gateway / 状态栏 widget /
 * AI 标题生成,均默认 true)+ Smithery Registry API key(S2 凭证安全:有系统 keychain 时存
 * {@link PasswordStore}(IntelliJ PasswordSafe),无 keychain 降级回 config.json 0600;set null/empty 清除,
 * 值本身不入日志)。
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
    private final PasswordStore passwordStore;

    // ==================== Field keys (promoted from CSS inline literals) ====================

    private static final String COMMIT_GENERATION_KEY = "commitGenerationEnabled";
    private static final String MCP_GATEWAY_KEY = "mcpGatewayEnabled";
    private static final String SMITHERY_API_KEY_KEY = "smitheryApiKey";
    private static final String STATUS_BAR_WIDGET_KEY = "statusBarWidgetEnabled";
    private static final String AI_TITLE_GENERATION_KEY = "aiTitleGenerationEnabled";

    /** PasswordStore credential key(S2 明文迁移后 smitheryApiKey 存此;满足 {@code codemoss.} 前缀规范)。 */
    private static final String SMITHERY_CREDENTIAL_KEY = "codemoss.smithery.apiKey";

    public AiFeatureToggleSettingsService(CodemossSettingsService settingsService, PasswordStore passwordStore) {
        this.settingsService = settingsService;
        this.passwordStore = passwordStore;
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
    // S2 凭证安全:有系统 keychain(AVAILABLE)时存 PasswordStore,无 keychain 降级回 config.json 0600。
    // 旧明文做懒迁移——首次 get 命中旧明文则搬到 PasswordStore 并清除明文;set 也会清除残留明文。

    /**
     * Get the Smithery Registry API key (used by MCP market to search/fetch server configs), or empty string.
     *
     * <p>S2:有 keychain 时优先读 {@link PasswordStore};若 PasswordStore 空而 config.json 有旧明文,
     * 懒迁移(store 成功后清除明文,失败 defer 不阻断);无 keychain 时降级读 config.json。
     * 契约:始终返回 {@code ""} 而非 {@code null}(保 Facade 不变)。
     */
    public String getSmitheryApiKey() throws IOException {
        if (passwordStore.getAvailability() == Availability.AVAILABLE) {
            String stored = passwordStore.loadPassword(SMITHERY_CREDENTIAL_KEY);
            if (stored != null) {
                return stored;
            }
            // 懒迁移:PasswordStore 空,检查 config.json 旧明文。
            String plaintext = readPlaintextFromConfig();
            if (plaintext == null) {
                return "";
            }
            try {
                passwordStore.storePassword(SMITHERY_CREDENTIAL_KEY, plaintext);
                clearPlaintextFromConfig();
                LOG.info("[AiFeatureToggle] Smithery API key migrated from config.json to PasswordStore");
            } catch (RuntimeException e) {
                // store 失败(如容量超限)不阻断读取:本次仍返回明文,迁移 defer 到下次。
                LOG.warn("[AiFeatureToggle] Smithery key migration deferred: " + e.getMessage());
            }
            return plaintext;
        }
        // 降级:无 keychain(headless / 服务器),回退 config.json 0600。
        String plaintext = readPlaintextFromConfig();
        return plaintext != null ? plaintext : "";
    }

    /**
     * Set the Smithery Registry API key. Empty/null clears it.
     *
     * <p>S2:有 keychain 时写 {@link PasswordStore} 并清除 config.json 残留明文;无 keychain 时降级写
     * config.json 0600(行为与历史等价)。Security:值本身不入日志——只记 set/cleared 状态。
     */
    public void setSmitheryApiKey(String apiKey) throws IOException {
        boolean cleared = apiKey == null || apiKey.isEmpty();
        if (passwordStore.getAvailability() == Availability.AVAILABLE) {
            if (cleared) {
                passwordStore.removePassword(SMITHERY_CREDENTIAL_KEY);
            } else {
                passwordStore.storePassword(SMITHERY_CREDENTIAL_KEY, apiKey);
            }
            // 清除 config.json 残留明文(覆盖"旧明文 + 直接 set 新值不 get"场景)。
            clearPlaintextFromConfig();
            LOG.info("[AiFeatureToggle] Smithery API key " + (cleared ? "cleared" : "updated"));
            return;
        }
        // 降级:无 keychain,回退 config.json writeConfig(原实现)。
        JsonObject config = settingsService.readConfig();
        if (cleared) {
            config.remove(SMITHERY_API_KEY_KEY);
        } else {
            config.addProperty(SMITHERY_API_KEY_KEY, apiKey);
        }
        settingsService.writeConfig(config);
        LOG.info("[AiFeatureToggle] Smithery API key " + (cleared ? "cleared" : "updated") + " (config.json fallback)");
    }

    /** 读 config.json 旧明文 smitheryApiKey;无则返回 {@code null}(历史读路径,降级/迁移共用)。 */
    private String readPlaintextFromConfig() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(SMITHERY_API_KEY_KEY) && !config.get(SMITHERY_API_KEY_KEY).isJsonNull()) {
            return config.get(SMITHERY_API_KEY_KEY).getAsString();
        }
        return null;
    }

    /** 清除 config.json 的 smitheryApiKey 明文字段;不存在则 no-op(避免无谓 writeConfig)。 */
    private void clearPlaintextFromConfig() throws IOException {
        JsonObject config = settingsService.readConfig();
        if (config.has(SMITHERY_API_KEY_KEY)) {
            config.remove(SMITHERY_API_KEY_KEY);
            settingsService.writeConfig(config);
        }
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
