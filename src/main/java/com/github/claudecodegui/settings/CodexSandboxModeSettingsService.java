package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * Codex Sandbox Mode 领域 Service。
 *
 * <p>封装 Codex 沙箱模式的 per-project / default 读写。config 结构:
 * <pre>{@code
 * "codexSandboxMode": {
 *   "<projectPath>": "workspace-write" | "danger-full-access",
 *   "default":       "workspace-write" | "danger-full-access"
 * }
 * }</pre>
 * 读取优先级:projectPath 特定值 &gt; {@code "default"} &gt; 平台默认值。无效 mode 一律回退平台默认。
 *
 * <p>依赖方向为上层 Facade → 本 Service → {@link ConfigStore}。
 * 写操作统一使用 {@link ConfigStore#update(ConfigStore.ConfigMutation)}，避免不同领域线程
 * 基于同一旧快照写回。{@link PlatformUtils#isWindows()} 仅用于后端平台默认值决策。
 */
public final class CodexSandboxModeSettingsService {
    private static final Logger LOG = Logger.getInstance(CodexSandboxModeSettingsService.class);

    private final ConfigStore configStore;

    // ==================== Field keys & valid modes (promoted from CSS inline literals) ====================

    private static final String CODEX_SANDBOX_MODE_KEY = "codexSandboxMode";
    private static final String CODEX_SANDBOX_DEFAULT_KEY = "default";
    private static final String CODEX_SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";

    public CodexSandboxModeSettingsService(ConfigStore configStore) {
        this.configStore = configStore;
    }

    // ==================== Codex Sandbox Mode Config Management ====================

    /**
     * Get Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @return sandbox mode (workspace-write or danger-full-access)
     */
    public String getCodexSandboxMode(String projectPath) throws IOException {
        JsonObject config = configStore.read();
        String defaultMode = getDefaultCodexSandboxMode();

        if (!config.has(CODEX_SANDBOX_MODE_KEY)) {
            return defaultMode;
        }

        JsonObject sandboxConfig = config.getAsJsonObject(CODEX_SANDBOX_MODE_KEY);

        if (projectPath != null && sandboxConfig.has(projectPath)) {
            String mode = sandboxConfig.get(projectPath).getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        if (sandboxConfig.has(CODEX_SANDBOX_DEFAULT_KEY)) {
            String mode = sandboxConfig.get(CODEX_SANDBOX_DEFAULT_KEY).getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        return defaultMode;
    }

    /**
     * Set Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @param sandboxMode sandbox mode (workspace-write or danger-full-access)
     */
    public void setCodexSandboxMode(String projectPath, String sandboxMode) throws IOException {
        if (!isValidCodexSandboxMode(sandboxMode)) {
            throw new IllegalArgumentException("Invalid Codex sandbox mode: " + sandboxMode);
        }

        configStore.update(config -> {
            JsonObject sandboxConfig;
            if (config.has(CODEX_SANDBOX_MODE_KEY)
                    && config.get(CODEX_SANDBOX_MODE_KEY).isJsonObject()) {
                sandboxConfig = config.getAsJsonObject(CODEX_SANDBOX_MODE_KEY);
            } else {
                sandboxConfig = new JsonObject();
                config.add(CODEX_SANDBOX_MODE_KEY, sandboxConfig);
            }

            if (projectPath != null) {
                sandboxConfig.addProperty(projectPath, sandboxMode);
            }
            sandboxConfig.addProperty(CODEX_SANDBOX_DEFAULT_KEY, sandboxMode);
        });
        LOG.info("[CodexSandboxMode] Set Codex sandbox mode to " + sandboxMode + " for project: " + projectPath);
    }

    private boolean isValidCodexSandboxMode(String mode) {
        return CODEX_SANDBOX_MODE_WORKSPACE_WRITE.equals(mode)
                || CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS.equals(mode);
    }

    private String getDefaultCodexSandboxMode() {
        // Security (F): default to workspace-write (sandboxed to the project) instead of
        // danger-full-access (no sandbox), so a prompt-injected Codex command is contained
        // to the project by default; full access must be an explicit opt-in. Windows keeps
        // danger-full-access as a platform fallback because the Codex sandbox is experimental
        // there (mirrors resolveCodexSandboxMode).
        return PlatformUtils.isWindows()
                ? CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS
                : CODEX_SANDBOX_MODE_WORKSPACE_WRITE;
    }
}
