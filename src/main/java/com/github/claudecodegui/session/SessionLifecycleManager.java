package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.handler.provider.ModelProviderHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.SessionTemplate;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = CommonConstants.PROP_PERMISSION_MODE;

    static void prepareForSessionReset(SessionHost host) {
        host.invalidateSessionCallbacks();
        host.clearPendingPermissionRequests();
        host.getStreamCoalescer().resetStreamState();
        host.resetTabStatus();
        host.callJavaScript("clearMessages");
    }

    private final SessionHost host;

    public SessionLifecycleManager(SessionHost host) {
        this.host = host;
    }

    /**
     * Create a new session, interrupting the old one first.
     */
    public void createNewSession() {
        LOG.info("Creating new session...");

        ClaudeSession oldSession = host.getSession();
        LOG.info("Creating new session from backend runtime defaults");

        prepareForSessionReset(host);

        CompletableFuture<Void> interruptFuture = oldSession != null
                                                          ? oldSession.interrupt()
                                                          : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            // 并发守卫:若自本次 reset 发起后 host.session 已被更新的 reset 替换,放弃本次 bootstrap,
            // 避免本次创建的 newSession 被覆盖而未 dispose(CLI 子进程泄漏)。
            if (host.getSession() != oldSession) {
                LOG.info("[Lifecycle] createNewSession superseded by a newer reset; abandoning bootstrap");
                return;
            }
            LOG.info("Old session interrupted, creating new session");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();
            // 标签页内新建会话继承本标签页旧会话的 provider/model/permission 状态，
            // 而非回退全局粘性默认(粘性默认仅供"新标签页"读取上次选择)。
            applyInheritedRuntimeState(newSession, oldSession);

            completeNewSessionBootstrap(newSession, determineWorkingDirectory(),
                    "New session created successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Create a new session from a template, interrupting the old one first.
     */
    public void createNewSessionFromTemplate(SessionTemplate template) {
        LOG.info("Creating new session from template: " + template.getName());

        ClaudeSession oldSession = host.getSession();

        prepareForSessionReset(host);

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            // 并发守卫:若自本次 reset 发起后 host.session 已被更新的 reset 替换,放弃本次 bootstrap,
            // 避免本次创建的 newSession 被覆盖而未 dispose(CLI 子进程泄漏)。
            if (host.getSession() != oldSession) {
                LOG.info("[Lifecycle] createNewSessionFromTemplate superseded by a newer reset; abandoning bootstrap");
                return;
            }
            LOG.info("Old session interrupted, creating new session from template");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();

            // Apply template settings
            if (template.getPermissionMode() != null) {
                newSession.setPermissionMode(template.getPermissionMode());
            }
            if (template.getProvider() != null) {
                newSession.setProvider(template.getProvider());
            }
            if (template.getModel() != null) {
                newSession.setModel(template.getModel());
            }
            if (template.getReasoningEffort() != null) {
                newSession.setReasoningEffort(template.getReasoningEffort());
            }
            newSession.getState().setPsiContextEnabled(template.isPsiContextEnabled());

            LOG.info("Applied template settings to new session: provider=" + template.getProvider()
                    + ", model=" + template.getModel() + ", mode=" + template.getPermissionMode());

            String workingDirectory = template.getCwd() != null && !template.getCwd().trim().isEmpty()
                    ? template.getCwd() : determineWorkingDirectory();
            completeNewSessionBootstrap(newSession, workingDirectory,
                    "New session created from template successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session from template: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session from template: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Load a history session by ID.
     */
    public void loadHistorySession(String sessionId, String projectPath) {
        loadHistorySession(sessionId, projectPath, null);
    }

    /**
     * Load a history session by ID and provider.
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider) {
        LOG.info("Loading history session: " + sessionId + " from project: " + projectPath);

        ClaudeSession oldSession = host.getSession();
        String previousPermissionMode;
        String previousProvider;
        String previousModel;
        if (oldSession != null) {
            previousPermissionMode = oldSession.getPermissionMode();
            previousProvider = oldSession.getProvider();
            previousModel = oldSession.getModel();
        } else {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            ClaudeSession defaultSession = createDefaultSession();
            previousPermissionMode = (savedMode != null && !savedMode.trim().isEmpty())
                                             ? savedMode.trim() : defaultSession.getPermissionMode();
            previousProvider = defaultSession.getProvider();
            previousModel = defaultSession.getModel();
        }
        LOG.info("Preserving session state when loading history: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + previousModel);

        prepareForSessionReset(host);
        host.clearPermissionDecisionMemory();

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            // 并发守卫:若自本次 reset 发起后 host.session 已被更新的 reset 替换,放弃本次 bootstrap,
            // 避免本次创建的 newSession 被覆盖而未 dispose(CLI 子进程泄漏)。
            if (host.getSession() != oldSession) {
                LOG.info("[Lifecycle] loadHistorySession superseded by a newer reset; abandoning bootstrap");
                return;
            }
            ClaudeSession newSession = new ClaudeSession(host.getProject());
            newSession.setPermissionMode(previousPermissionMode);
            newSession.setProvider(provider != null && !provider.trim().isEmpty() ? provider : previousProvider);
            newSession.setModel(previousModel);
            LOG.info("Restored session state to loaded session: mode=" + previousPermissionMode
                             + ", provider=" + newSession.getProvider() + ", model=" + previousModel);

            host.setSession(newSession);
            host.getHandlerContext().setSession(newSession);
            host.setupSessionCallbacks();

            String workingDir = (projectPath != null && new File(projectPath).exists())
                                    ? projectPath : determineWorkingDirectory();
            newSession.setSessionInfo(sessionId, workingDir);
            pushSessionRuntimeState(newSession);

            newSession.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
            })).exceptionally(ex -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Release transition guard so the frontend is not permanently stuck
                    host.callJavaScript("historyLoadComplete");
                    host.callJavaScript("addErrorMessage",
                            JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
                });
                return null;
            });
        }).exceptionally(ex -> {
            LOG.error("Failed to load history session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("addErrorMessage",
                        JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Determine the working directory for the session.
     */
    public String determineWorkingDirectory() {
        String projectPath = host.getProject().getBasePath();

        if (projectPath == null || !new File(projectPath).exists()) {
            String userHome = PlatformUtils.getHomeDirectory();
            LOG.warn("Using user home directory as fallback: " + userHome);
            return userHome;
        }

        try {
            CodemossSettingsService settingsService = CodemossSettingsService.getInstance();
            String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);

            if (customWorkingDir != null && !customWorkingDir.isEmpty()) {
                File workingDirFile = new File(customWorkingDir);
                if (!workingDirFile.isAbsolute()) {
                    workingDirFile = new File(projectPath, customWorkingDir);
                }
                if (workingDirFile.exists() && workingDirFile.isDirectory()) {
                    String resolvedPath = workingDirFile.getAbsolutePath();
                    LOG.info("Using custom working directory: " + resolvedPath);
                    return resolvedPath;
                } else {
                    LOG.warn("Custom working directory does not exist: "
                                     + workingDirFile.getAbsolutePath() + ", falling back to project root");
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve working directory: " + e.getMessage());
        }

        return projectPath;
    }

    /**
     * Fetch slash commands using local registry (no AI call needed).
     * Merges built-in commands with skill-derived commands per provider.
     */
    public void fetchSlashCommandsOnStartup() {
        ClaudeSession currentSession = host.getSession();
        String cwd = currentSession != null ? currentSession.getCwd() : null;
        if (cwd == null) {
            cwd = host.getProject().getBasePath();
        }

        // Determine current provider
        String provider = CommonConstants.DEFAULT_PROVIDER;
        if (currentSession != null && currentSession.getProvider() != null) {
            provider = currentSession.getProvider();
        }

        LOG.info("Fetching slash commands locally, provider=" + provider + ", cwd=" + cwd);

        String currentFilePath = getCurrentEditorFilePath();
        var commands = SlashCommandRegistry.getCommands(provider, cwd, currentFilePath);
        if (currentSession != null) {
            currentSession.setSkillSnapshot(SessionSkillSnapshot.fromCommands(commands));
        }
        String commandsJson = SlashCommandRegistry.toJson(commands);

        host.setFetchedSlashCommandsCount(commands.size());
        host.setSlashCommandsFetched(true);
        LOG.info("Slash commands resolved locally: " + commands.size() + " commands");

        // Pre-compute Codex skills outside EDT to avoid file I/O on UI thread
        final List<SlashCommandRegistry.SlashCommand> codexSkills;
        final String codexSkillsJson;
        // codex 判定经 ProviderType SSOT(总则五·开闭 / E6):消除裸 "codex" 字面量。
        // fromString 忽略大小写,与原 equalsIgnoreCase 等价。
        if (ProviderType.fromString(provider) == ProviderType.CODEX) {
            codexSkills = SlashCommandRegistry.getCodexSkills(cwd);
            codexSkillsJson = SlashCommandRegistry.toJson(codexSkills);
            LOG.info("Codex skills resolved: " + codexSkills.size() + " skills");
        } else {
            codexSkills = null;
            codexSkillsJson = null;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                host.getHandlerContext().dispatchEvent(DownstreamEvent.SLASH_COMMANDS.value(), commandsJson);

                // Push Codex skills as separate channel for $ autocomplete
                if (codexSkillsJson != null) {
                    host.getHandlerContext().dispatchEvent(DownstreamEvent.SLASH_DOLLAR_COMMANDS.value(), codexSkillsJson);
                }
            } catch (Exception e) {
                LOG.warn("Failed to send slash commands to frontend: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Send current permission mode to the frontend.
     */
    public void sendCurrentPermissionMode() {
        try {
            String currentMode = CommonConstants.PERMISSION_MODE_DEFAULT;

            ClaudeSession currentSession = host.getSession();
            if (currentSession != null) {
                String sessionMode = currentSession.getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            }

            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!host.isDisposed() && host.getBrowser() != null) {
                    host.getHandlerContext().dispatchEvent(DownstreamEvent.MODE_RECEIVED.value(), modeToSend);
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to send current permission mode: " + e.getMessage(), e);
        }
    }

    /**
     * Reset token usage statistics in the frontend (used after new session creation).
     */
    private void resetTokenUsage() {
        // 新会话的 provider 尚未上报 trusted token 计数:不附 limit/usedTokens,让前端保持
        // unknown 而非把静态模型容量(200k/1050k)误当作当前上下文。
        JsonObject usageUpdate = new JsonObject();
        usageUpdate.addProperty("percentage", 0);

        String usageJson = GsonHolder.GSON.toJson(usageUpdate);

        if (!host.isDisposed()) {
            // [归一化] 直接走下行总线 dispatchEvent,替代旧 window.onUsageUpdate 字面调用。
            host.getHandlerContext().dispatchEvent(DownstreamEvent.USAGE_UPDATE.value(), usageJson);
        }
    }

    private String getCurrentEditorFilePath() {
        return com.github.claudecodegui.util.EditorFileUtils.getCurrentEditorFilePath(this.host.getProject());
    }

    private ClaudeSession createDefaultSession() {
        ClaudeSession session = new ClaudeSession(host.getProject());
        initializeSessionRuntimeDefaults(session);
        return session;
    }

    private void initializeSessionRuntimeDefaults(ClaudeSession session) {
        String provider = readDefaultProvider();
        session.setProvider(provider);
        session.setPermissionMode(readDefaultPermissionMode(provider));
        session.setModel(readDefaultModel(provider));
    }

    /**
     * 标签页内新建会话时,将旧会话的运行时状态(provider/model/permissionMode)
     * 继承到新会话,避免回退到全局粘性默认。镜像 {@link #loadHistorySession} 的保留模式
     * (previousProvider/previousModel 直接 setModel 不校验跨 provider 兼容性,保持一致)。
     * <p>"记录上次 provider"仅供<b>新标签页</b>读取上次选择(见 SessionRuntimeDefaults);
     * 已有标签页内新建会话应保留本标签页当前 provider,而非被全局粘性值覆盖。
     *
     * @param target 新建会话(createDefaultSession 返回,已填充默认值)
     * @param source 切换前的旧会话;为 null 或 provider 无效时 no-op(保留 target 默认)
     */
    static void applyInheritedRuntimeState(ClaudeSession target, ClaudeSession source) {
        if (target == null || source == null) {
            return;
        }
        String provider = source.getProvider();
        if (!SessionState.VALID_PROVIDERS.contains(provider)) {
            return;
        }
        target.setProvider(provider);
        target.setModel(source.getModel());
        target.setPermissionMode(source.getPermissionMode());
    }

    private String readDefaultProvider() {
        String provider = host.getHandlerContext().getCurrentProvider();
        if (SessionState.VALID_PROVIDERS.contains(provider)) {
            return provider;
        }
        return SessionRuntimeDefaults.readProvider(host.getProject());
    }

    private String readDefaultModel(String provider) {
        String model = SessionRuntimeDefaults.readModel(host.getProject(), provider);
        if (isNonBlank(model)) {
            return model.trim();
        }

        model = host.getHandlerContext().getCurrentModel();
        if (isNonBlank(model) && modelBelongsToProvider(provider, model)) {
            return model.trim();
        }

        model = firstEnabledModelForProvider(provider);
        if (isNonBlank(model)) {
            return model.trim();
        }

        if (CommonConstants.PROVIDER_CODEX.equals(provider)) {
            // 治本:优先使用用户 ~/.codex/config.toml 配置的 model(如自定义 Galaxy provider 的 gpt-5.5),
            // 而非硬编码 gpt-5.3-codex(自定义 provider 不支持时会 502 重连死循环)。无配置时回退官方默认。
            String configModel = CliSettings.readCodexConfigModel();
            if (configModel != null && !configModel.trim().isEmpty()) {
                return configModel.trim();
            }
            return CommonConstants.DEFAULT_CODEX_MODEL;
        }
        if (CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
            // OpenCode 无 registry/model 时交给 opencode 原生配置决定默认模型;不要把 Claude role
            // 透传成 `opencode run -m claude-role-sonnet`,否则 CLI 可能 exit0 但无事件流。
            return "";
        }
        return CommonConstants.DEFAULT_MODEL;
    }

    private boolean modelBelongsToProvider(String provider, String model) {
        try {
            return host.getHandlerContext().getSettingsService()
                    .getModelRegistry()
                    .find(provider, model)
                    .isPresent();
        } catch (Exception e) {
            LOG.warn("Failed to check model provider ownership: " + e.getMessage());
            return CommonConstants.PROVIDER_CLAUDE.equals(provider)
                    && ModelProviderHandler.isKnownModel(null, model);
        }
    }

    private String firstEnabledModelForProvider(String provider) {
        try {
            return host.getHandlerContext().getSettingsService()
                    .getModelRegistry()
                    .models()
                    .stream()
                    .filter(ModelConfig::enabled)
                    .filter(model -> provider.equals(model.provider()))
                    .map(ModelConfig::id)
                    .filter(this::isNonBlank)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            LOG.warn("Failed to read default model from registry: " + e.getMessage());
            return null;
        }
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String readDefaultPermissionMode(String provider) {
        String mode = PropertiesComponent.getInstance().getValue(PERMISSION_MODE_PROPERTY_KEY);
        if (mode == null || mode.trim().isEmpty() || !SessionState.isValidPermissionMode(mode)) {
            mode = CommonConstants.PERMISSION_MODE_DEFAULT;
        } else {
            mode = mode.trim();
        }
        return mode;
    }

    private void completeNewSessionBootstrap(ClaudeSession newSession, String workingDirectory, String successLogPrefix) {
        host.clearPermissionDecisionMemory();
        host.setSession(newSession);
        host.getHandlerContext().setSession(newSession);
        host.setupSessionCallbacks();

        newSession.setSessionInfo(null, workingDirectory);
        LOG.info(successLogPrefix + workingDirectory + ", epoch=" + newSession.getRuntimeSessionEpoch());
        fetchSlashCommandsOnStartup();
        pushSessionRuntimeState(newSession);

        ApplicationManager.getApplication().invokeLater(() -> {
            // Release the frontend session transition guard so updateMessages works again.
            // Must come BEFORE updateStatus to ensure the guard is lifted before any
            // subsequent message updates arrive.
            host.callJavaScript("historyLoadComplete");
            host.callJavaScript("updateStatus",
                    JsUtils.escapeJs(ClaudeCodeGuiBundle.message("toast.newSessionCreatedReady")));
            resetTokenUsage();
        });
    }

    private void pushSessionRuntimeState(ClaudeSession session) {
        if (session == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("provider", session.getProvider());
        payload.addProperty("model", session.getModel());
        payload.addProperty("permissionMode", session.getPermissionMode());
        String json = GsonHolder.GSON.toJson(payload);
        ApplicationManager.getApplication().invokeLater(() -> host.getHandlerContext().dispatchEvent(DownstreamEvent.SESSION_RUNTIME_STATE.value(), json));
    }

    /**
     * Host interface providing access to window-level dependencies.
     */
    public interface SessionHost {
        Project getProject();

        ClaudeSession getSession();

        void setSession(ClaudeSession session);

        HandlerContext getHandlerContext();

        StreamMessageCoalescer getStreamCoalescer();

        void clearPendingPermissionRequests();

        void clearPermissionDecisionMemory();

        void callJavaScript(String functionName, String... args);

        boolean isDisposed();

        JBCefBrowser getBrowser();

        void setupSessionCallbacks();

        void invalidateSessionCallbacks();

        void setSlashCommandsFetched(boolean fetched);

        void setFetchedSlashCommandsCount(int count);

        void resetTabStatus();
    }
}
