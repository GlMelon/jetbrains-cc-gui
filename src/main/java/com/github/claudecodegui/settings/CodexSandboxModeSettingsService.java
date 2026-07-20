package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * Codex Sandbox Mode 领域 Service(A3 领域拆分第三步,docs §A3)。
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
 * <p>与 {@link AppearanceSettingsService} / {@link AiFeatureToggleSettingsService} 同为「模式 A 半拆」:
 * 构造注入 {@link CodemossSettingsService},持久化走 {@code css.readConfig()/writeConfig()}。核心理由同第一步 ——
 * 文件缺失时 {@code CSS.readConfig()} 返回 {@code createDefaultConfig()} 全局骨架,Service 在其上读/写单个段,
 * 行为与历史逐字等价;直连 {@link ConfigRepository} 会丢失全局默认段。
 *
 * <p><b>零核心路径耦合</b>:2 对方法只做 {@code readConfig + 校验 + writeConfig} 三件套,
 * 不触 Provider / Model Registry / MCP;{@link PlatformUtils#isWindows()} 仅作平台默认值决策的值读取,
 * 不反向调用核心路径。调用点(CliSettings / CodexCliSession / CodexSDKBridge / ProjectConfigHandler)
 * 全经 CSS public 委托,Facade 签名保留,调用面零改动。
 *
 * <p><b>Facade 不变</b>:CSS 2 个 public 签名保留为单行转发委托;调用面与既有测试零改动。
 */
public final class CodexSandboxModeSettingsService {
    private static final Logger LOG = Logger.getInstance(CodexSandboxModeSettingsService.class);

    private final CodemossSettingsService settingsService;

    // ==================== Field keys & valid modes (promoted from CSS inline literals) ====================

    private static final String CODEX_SANDBOX_MODE_KEY = "codexSandboxMode";
    private static final String CODEX_SANDBOX_DEFAULT_KEY = "default";
    private static final String CODEX_SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";

    public CodexSandboxModeSettingsService(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ==================== Codex Sandbox Mode Config Management ====================

    /**
     * Get Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @return sandbox mode (workspace-write or danger-full-access)
     */
    public String getCodexSandboxMode(String projectPath) throws IOException {
        JsonObject config = settingsService.readConfig();
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

        JsonObject config = settingsService.readConfig();

        JsonObject sandboxConfig;
        if (config.has(CODEX_SANDBOX_MODE_KEY)) {
            sandboxConfig = config.getAsJsonObject(CODEX_SANDBOX_MODE_KEY);
        } else {
            sandboxConfig = new JsonObject();
            config.add(CODEX_SANDBOX_MODE_KEY, sandboxConfig);
        }

        if (projectPath != null) {
            sandboxConfig.addProperty(projectPath, sandboxMode);
        }
        sandboxConfig.addProperty(CODEX_SANDBOX_DEFAULT_KEY, sandboxMode);

        settingsService.writeConfig(config);
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
        // there (mirrors CodexSDKBridge.resolveCodexSandboxMode).
        return PlatformUtils.isWindows()
                ? CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS
                : CODEX_SANDBOX_MODE_WORKSPACE_WRITE;
    }
}
