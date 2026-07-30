package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.permission.PermissionActionHandlers;
import com.github.claudecodegui.handler.history.HistoryRefreshService;
import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.DaemonBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.ProjectBridgeRegistry;
import com.github.claudecodegui.provider.common.SharedBridgeReferenceCounter;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionCallbackAdapter;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.SessionLoadService;
import com.github.claudecodegui.session.SessionRuntimeDefaults;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.ChatWindowDelegate;
import com.github.claudecodegui.ui.EditorContextTracker;
import com.github.claudecodegui.ui.WebviewInitializer;
import com.github.claudecodegui.ui.WebviewWatchdog;
import com.github.claudecodegui.ui.detached.DetachedChatFrame;
import com.github.claudecodegui.ui.detached.DetachedWindowManager;
import com.github.claudecodegui.util.AttachmentStorageService;
import com.github.claudecodegui.util.HtmlLoader;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.Alarm;
import javax.swing.SwingUtilities;
import org.cef.browser.CefBrowser;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import com.intellij.util.Alarm;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Chat window instance. Coordinates UI components, session management,
 * and message dispatching. One instance per tab.
 *
 * @author melon
 */
public class ClaudeChatWindow {

    /**
     * log.
     */
    private static final Logger LOG = Logger.getInstance(ClaudeChatWindow.class);

    /**
     * main panel.
     */
    private final JPanel mainPanel;
    /**
     * claude sdk bridge.
     */
    private final ClaudeSDKBridge claudeSDKBridge;
    /**
     * codex sdk bridge.
     */
    private final CodexSDKBridge codexSDKBridge;
    /**
     * opencode sdk bridge.
     */
    private final com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge openCodeSDKBridge;
    /**
     * project.
     */
    private final Project project;
    /**
     * settings service.
     */
    private final CodemossSettingsService settingsService;
    /**
     * html loader.
     */
    private final HtmlLoader htmlLoader;

    /**
     * parent content.
     */
    private Content parentContent;
    /**
     * original tab name.
     */
    private String originalTabName;
    /**
     * session id.
     */
    private volatile String sessionId = null;
    // Stable PermissionService routing key, assigned once at construction.
    // Kept separate from sessionId, which is overwritten with AI session IDs
    // (onSessionIdReceived) and would otherwise break dispose-time cleanup and
    // clearPermissionDecisionMemory(), both of which must reach the instance
    // the bridges actually route permission requests to.
    private String permissionServiceKey = null;

    /**
     * browser.
     */
    private JBCefBrowser browser;
    // volatile: read from the daemon reader thread by the session_updated listener
    // and its loadFromServer continuation, while reassigned on the EDT.
    private volatile ClaudeSession session;
    /**
     * webview watchdog.
     */
    private final WebviewWatchdog webviewWatchdog;
    /**
     * stream coalescer.
     */
    private final StreamMessageCoalescer streamCoalescer;

    /**
     * disposed.
     */
    private volatile boolean disposed = false;
    /**
     * initialized.
     */
    private volatile boolean initialized = false;
    /**
     * frontend ready.
     */
    private volatile boolean frontendReady = false;
    /**
     * slash commands fetched.
     */
    private volatile boolean slashCommandsFetched = false;
    /**
     * restored history load started.
     */
    private final AtomicBoolean restoredHistoryLoadStarted = new AtomicBoolean(false);
    /**
     * Persisted session id that still needs a one-time lazy history restore.
     * Fresh tabs created in the current IDE run must never populate this field,
     * otherwise switching tabs can overwrite live in-memory messages with a
     * stale disk snapshot.
     */
    private volatile String deferredHistoryRestoreSessionId = null;
    /**
     * task completion notification sent.
     */
    private final AtomicBoolean taskCompletionNotificationSent = new AtomicBoolean(false);
    private final Alarm notificationAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

    /**
     * title event listener.
     */ // Daemon event listener for AI title forwarding. Held so it can be removed on dispose.
    private DaemonBridge.DaemonEventListener titleEventListener;

    // Coalesces session_updated reloads. SessionState's message list is not
    // thread-safe and loadFromServer() runs async, so concurrent background-task
    // completions must not reload at the same time. Guarded by sessionReloadLock.
    private final Object sessionReloadLock = new Object();
    private boolean sessionReloadInFlight = false;
    private boolean sessionReloadPending = false;
    // A session_updated reload that arrived while a turn was streaming is parked
    // here and drained at stream end (onStreamEnded). See {@link DeferredReload}.
    private final DeferredReload deferredReload = new DeferredReload();
    // Backstop for the parked reload. onStreamEnded is the fast drain path, but it
    // is edge-triggered: a defer that lands just after the stream-end edge (a
    // cross-thread check-then-act between the daemon reader's isStreamActive() read
    // and the stream reader's streamActive=false + drain), or the LAST background
    // answer of a fan-out with no following stream end, would otherwise never be
    // drained — the answer stays invisible forever. This alarm re-checks after a
    // short delay and drains the parked reload the moment the stream is idle,
    // without ever reloading mid-stream. Pooled thread: draining kicks off an async
    // loadFromServer() that reads JSONL, so it must not run on the EDT.
    private static final int DEFERRED_RELOAD_SAFETY_DRAIN_MS = 500;
    private final Disposable safetyAlarmDisposable =
            Disposer.newDisposable("ccgui-deferred-reload-safety");
    private final Alarm deferredReloadSafetyAlarm =
            new Alarm(Alarm.ThreadToUse.POOLED_THREAD, safetyAlarmDisposable);

    /**
     * handler context.
     */
    private HandlerContext handlerContext;
    /**
     * frontend action dispatcher (typed handlers; sole dispatch path for upstream actions).
     */
    private FrontendActionDispatcher frontendActionDispatcher;
    /**
     * permission action handlers (shared state for permission dialogs and responses).
     */
    private PermissionActionHandlers permissionHandler;
    private HistoryRefreshService historyRefreshService;
    /**
     * session lifecycle manager.
     */
    private final SessionLifecycleManager sessionLifecycleManager;

    /**
     * webview initializer.
     */ // Delegates
    private WebviewInitializer webviewInitializer;
    /**
     * editor context tracker.
     */
    private final EditorContextTracker editorContextTracker;
    /**
     * chat window delegate.
     */
    private final ChatWindowDelegate chatWindowDelegate;
    /**
     * session callback adapter.
     */
    private SessionCallbackAdapter sessionCallbackAdapter;

    public ClaudeChatWindow(Project project) {
        this(project, false);
    }

    public ClaudeChatWindow(Project project, boolean skipRegister) {
        this.project = project;
        ProjectBridgeRegistry.SharedBridges sharedBridges = ProjectBridgeRegistry.get(project);
        this.claudeSDKBridge = sharedBridges.getClaudeBridge();
        this.codexSDKBridge = sharedBridges.getCodexBridge();
        this.openCodeSDKBridge = sharedBridges.getOpenCodeBridge();
        SharedBridgeReferenceCounter.retain(project);
        this.settingsService = CodemossSettingsService.getInstance();
        this.htmlLoader = new HtmlLoader(getClass());
        this.mainPanel = new JPanel(new BorderLayout());

        this.mainPanel.setBackground(com.github.claudecodegui.util.ThemeConfigService.getBackgroundColor());

        this.streamCoalescer = new StreamMessageCoalescer(new StreamMessageCoalescer.JsCallbackTarget() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                ClaudeChatWindow.this.callJavaScript(functionName, args);
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }
        }, project);

        this.webviewWatchdog = new WebviewWatchdog(
                mainPanel,
                () -> browser,
                () -> webviewInitializer.reloadWebview("watchdog_reload"),
                () -> webviewInitializer.recreateWebview("watchdog_recreate"),
                () -> disposed,
                () -> streamCoalescer.isStreamActive(),
                () -> frontendReady
        );

        this.session = new ClaudeSession(project, claudeSDKBridge, codexSDKBridge, openCodeSDKBridge);
        SessionRuntimeDefaults.applyToSession(project, this.session, settingsService.getModelRegistry());

        this.chatWindowDelegate = new ChatWindowDelegate(createDelegateHost());
        chatWindowDelegate.loadPermissionModeFromSettings();
        chatWindowDelegate.loadNodePathFromSettings();
        chatWindowDelegate.syncActiveProvider();
        chatWindowDelegate.initializeHandlers();
        this.permissionServiceKey = chatWindowDelegate.setupPermissionService();
        this.sessionId = this.permissionServiceKey;

        this.sessionLifecycleManager = new SessionLifecycleManager(new SessionLifecycleManager.SessionHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge getOpenCodeSDKBridge() {
                return openCodeSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public void setSession(ClaudeSession s) {
                session = s;
                persistTabSessionState();
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public void clearPendingPermissionRequests() {
                permissionHandler.clearPendingRequests();
            }

            @Override
            public void clearPermissionDecisionMemory() {
                try {
                    if (permissionServiceKey != null && !permissionServiceKey.isEmpty()) {
                        PermissionService permissionService = PermissionService.getInstance(project, permissionServiceKey);
                        permissionService.clearDecisionMemory();
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to clear permission decision memory: " + e.getMessage());
                }
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setupSessionCallbacks() {
                ClaudeChatWindow.this.setupSessionCallbacks();
            }

            @Override
            public void invalidateSessionCallbacks() {
                if (sessionCallbackAdapter != null) {
                    sessionCallbackAdapter.deactivate();
                }
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                // No-op: count is reported but no longer consumed.
            }

            @Override
            public void resetTabStatus() {
                chatWindowDelegate.updateTabStatus(ChatWindowDelegate.TabAnswerStatus.IDLE);
            }
        });

        this.editorContextTracker = new EditorContextTracker(project, new EditorContextTracker.ContextCallback() {
            @Override
            public void addSelectionInfo(String info) {
                callJavaScript("addSelectionInfo", info);
            }

            @Override
            public void clearSelectionInfo() {
                callJavaScript("clearSelectionInfo");
            }
        });
        editorContextTracker.registerListeners();

        this.webviewInitializer = new WebviewInitializer(createWebviewHost());

        setupSessionCallbacks();
        initializeSessionInfo();

        // Delay JCEF browser creation to avoid service initialization conflicts
        // during JBCefApp$Holder class init (ProxyMigrationService dependency).
        // Operations that depend on browser readiness are also deferred.
        ToolWindowManager.getInstance(this.project).invokeLater(() -> {
            if (!this.disposed) {
                this.webviewInitializer.createUIComponents();
                registerSessionLoadListener();
                this.initialized = true;
                LOG.info("Window instance fully initialized, project: " + this.project.getName());
            }
        });

        if (!skipRegister) {
            registerInstance();
        }
        chatWindowDelegate.initializeStatusBar();
        SendShortcutSync.syncFromSettings();
    }

    // ==================== Public API ====================

    public void setParentContent(Content content) {
        if (this.parentContent != null && this.parentContent != content) {
            ClaudeSDKToolWindow.unregisterContentMapping(this.parentContent);
            LOG.debug("[MultiTab] Unregistered old Content -> ClaudeChatWindow mapping");
        }

        this.parentContent = content;
        if (content != null) {
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true);
            ClaudeSDKToolWindow.registerContentMapping(content, this);
            LOG.debug("[MultiTab] Registered Content -> ClaudeChatWindow mapping for: " + content.getDisplayName());

            if (this.originalTabName == null) {
                String displayName = content.getDisplayName();
                this.originalTabName = displayName.endsWith("...")
                        ? displayName.substring(0, displayName.length() - 3)
                        : displayName;
                LOG.debug("[TabLoading] Auto-initialized original tab name: " + this.originalTabName);
            }

            persistTabSessionState();
        }
    }

    public void setOriginalTabName(String name) {
        this.originalTabName = (name != null && name.endsWith("..."))
                ? name.substring(0, name.length() - 3)
                : name;
        LOG.debug("[TabLoading] Set original tab name: " + this.originalTabName);
    }

    public boolean isDisposed() {
        return disposed;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Content getParentContent() {
        return parentContent;
    }

    private boolean isActiveContent() {
        Content content = parentContent;
        ContentManager contentManager = content == null ? null : content.getManager();
        if (contentManager != null && contentManager.getIndexOfContent(content) >= 0) {
            return contentManager.getSelectedContent() == content;
        }
        DetachedChatFrame detachedFrame = DetachedWindowManager.getDetachedFrame(project, this);
        return detachedFrame == null || detachedFrame.isActive();
    }

    private void activateContent() {
        Runnable activation = () -> {
            if (disposed) {
                return;
            }
            Content content = parentContent;
            ContentManager contentManager = content == null ? null : content.getManager();
            if (contentManager != null && contentManager.getIndexOfContent(content) >= 0) {
                contentManager.setSelectedContent(content);
                ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(ClaudeSDKToolWindow.TOOL_WINDOW_ID);
                if (toolWindow != null
                        && toolWindow.getContentManager() == contentManager
                        && !toolWindow.isActive()) {
                    toolWindow.activate(null);
                }
                return;
            }
            DetachedChatFrame detachedFrame = DetachedWindowManager.getDetachedFrame(project, this);
            if (detachedFrame != null) {
                detachedFrame.setVisible(true);
                detachedFrame.toFront();
                detachedFrame.requestFocus();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            activation.run();
        } else {
            ApplicationManager.getApplication().invokeLater(activation);
        }
    }

    public JPanel getContent() {
        return mainPanel;
    }

    public ClaudeSDKBridge getClaudeSDKBridge() {
        return claudeSDKBridge;
    }

    public CodexSDKBridge getCodexSDKBridge() {
        return codexSDKBridge;
    }

    /**
     * Get the project associated with this chat window.
     *
     * @return the current project.
     */
    public Project getProject() {
        return this.project;
    }

    /** Restore the native JCEF surface after this content tab becomes active. */
    public void onTabActivated() {
        Runnable repaint = () -> {
            if (disposed || !isSelectedContent()) {
                return;
            }
            webviewWatchdog.resetTimestamps();
            JBCefBrowser currentBrowser = browser;
            if (currentBrowser == null) {
                return;
            }
            try {
                refreshActivatedWebview(
                        mainPanel,
                        currentBrowser.getComponent(),
                        currentBrowser.getCefBrowser(),
                        currentBrowser.isOffScreenRendering(),
                        () -> callJavaScript("window.onTabActivated")
                );
            } catch (Exception | LinkageError e) {
                LOG.warn("Failed to refresh activated JCEF tab: " + e.getMessage(), e);
            }
        };
        ApplicationManager.getApplication().invokeLater(repaint);
    }

    private boolean isSelectedContent() {
        Content content = parentContent;
        ContentManager contentManager = content == null ? null : content.getManager();
        return contentManager != null && contentManager.getSelectedContent() == content;
    }

    static void refreshActivatedWebview(
            JPanel mainPanel,
            JComponent browserComponent,
            CefBrowser cefBrowser,
            boolean offScreenRendering,
            Runnable frontendRepaint
    ) {
        mainPanel.revalidate();
        mainPanel.repaint();
        browserComponent.revalidate();
        browserComponent.repaint();

        try {
            if (offScreenRendering) {
                int width = browserComponent.getWidth();
                int height = browserComponent.getHeight();
                if (width > 0 && height > 0) {
                    cefBrowser.wasResized(width, height);
                }
            } else {
                Component nativeComponent = cefBrowser.getUIComponent();
                if (nativeComponent != null) {
                    nativeComponent.setVisible(false);
                    nativeComponent.invalidate();
                    nativeComponent.setVisible(true);
                    Container parent = nativeComponent.getParent();
                    if (parent != null) {
                        parent.validate();
                        parent.repaint();
                    }
                    nativeComponent.repaint();
                }
            }
            cefBrowser.notifyScreenInfoChanged();
        } finally {
            frontendRepaint.run();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the provider this tab is currently using ("claude" or "codex").
     * Used by NodeProcessRegistry to label processes with the user-facing provider
     * rather than the underlying SDK type (a Claude daemon may still be alive
     * after the user switched the tab to Codex — the panel reflects the tab's
     * intent, not the lingering SDK).
     */
    public String getCurrentProvider() {
        HandlerContext ctx = this.handlerContext;
        return ctx != null ? ctx.getCurrentProvider() : CommonConstants.PROVIDER_CLAUDE;
    }

    public ClaudeSession getSession() {
        return session;
    }

    /**
     * Copy user-selected per-tab preferences into a fresh tab without copying session identity,
     * message history, runtime ownership, or persisted pin state.
     */
    public void inheritSessionPreferencesFrom(ClaudeChatWindow sourceWindow) {
        if (sourceWindow == null || sourceWindow.session == null || session == null) {
            return;
        }
        copySessionPreferences(sourceWindow.session.getState(), session.getState());
        if (handlerContext != null) {
            handlerContext.setCurrentProvider(session.getProvider());
            handlerContext.setCurrentModel(session.getModel());
            handlerContext.setCurrentModelContextWindow(session.getState().getContextWindowOverride());
        }
    }

    static void copySessionPreferences(SessionState source, SessionState target) {
        if (source == null || target == null) {
            return;
        }
        String targetSessionId = target.getSessionId();
        String targetCwd = target.getCwd();
        target.setProvider(source.getProvider());
        target.setModel(source.getModel());
        target.setPermissionMode(source.getPermissionMode());
        target.setReasoningEffort(source.getReasoningEffort());
        target.setSessionId(targetSessionId);
        target.setCwd(targetCwd);
    }

    public SessionLifecycleManager getSessionLifecycleManager() {
        return sessionLifecycleManager;
    }

    public void restorePersistedTabSessionState(TabStateService.TabSessionState savedState) {
        if (savedState == null || session == null) {
            return;
        }

        // Degradation logging (F3): surface bindings that cannot be restored rather than
        // silently skipping, so a missing provider/session is diagnosable on restart.
        if (!isNonEmpty(savedState.provider)) {
            LOG.warn("[TabRestore] Tab restored without a provider binding; falling back to default provider.");
        }
        if (!isNonEmpty(savedState.sessionId)) {
            LOG.info("[TabRestore] Tab restored without a sessionId; history will not load until a new session starts.");
        }

        if (savedState.permissionMode != null && !savedState.permissionMode.trim().isEmpty()) {
            session.setPermissionMode(savedState.permissionMode);
        }
        if (savedState.provider != null && !savedState.provider.trim().isEmpty()) {
            session.setProvider(savedState.provider);
        }
        if (savedState.model != null && !savedState.model.trim().isEmpty()) {
            session.setModel(savedState.model);
        }
        if (savedState.reasoningEffort != null && !savedState.reasoningEffort.trim().isEmpty()) {
            session.setReasoningEffort(savedState.reasoningEffort);
        }

        String restoredSessionId = isNonEmpty(savedState.sessionId) ? savedState.sessionId : null;
        String restoredCwd = isNonEmpty(savedState.cwd) ? savedState.cwd : session.getCwd();
        session.setSessionInfo(restoredSessionId, restoredCwd);
        deferredHistoryRestoreSessionId = TabSessionRestorePolicy.getDeferredRestoreSessionId(savedState);
        persistTabSessionState();

        LOG.info("[TabRestore] Restored tab session state: provider=" + savedState.provider
                + ", sessionId=" + savedState.sessionId + ", cwd=" + savedState.cwd + ")");
    }

    public void restorePersistedTabSessionState(TabStateService.TabSessionState savedState, boolean loadImmediately) {
        restorePersistedTabSessionState(savedState);
        if (TabSessionRestorePolicy.shouldLoadImmediately(savedState, loadImmediately)) {
            loadRestoredHistoryIfNeeded(savedState);
        }
    }

    public void loadRestoredHistoryIfNeeded() {
        if (session == null) {
            return;
        }
        String currentSessionId = session.getSessionId();
        String pendingSessionId = deferredHistoryRestoreSessionId;
        if (!TabSessionRestorePolicy.shouldLoadDeferredHistory(pendingSessionId, currentSessionId)) {
            if (pendingSessionId != null && !pendingSessionId.equals(currentSessionId)) {
                deferredHistoryRestoreSessionId = null;
            }
            return;
        }
        TabStateService.TabSessionState currentState = new TabStateService.TabSessionState();
        currentState.sessionId = pendingSessionId;
        loadRestoredHistoryIfNeeded(currentState);
    }

    private void loadRestoredHistoryIfNeeded(TabStateService.TabSessionState savedState) {
        if (!TabSessionRestorePolicy.shouldLoadHistory(savedState) || session == null) {
            return;
        }
        if (!restoredHistoryLoadStarted.compareAndSet(false, true)) {
            return;
        }
        deferredHistoryRestoreSessionId = null;
        long startNanos = System.nanoTime();
        String tabDescriptor = TabPerformanceLogger.describeTab(getCurrentTabName(), savedState.sessionId);
        LOG.info("[TabPerf] Restored history load started: " + tabDescriptor);

        session.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            long elapsedMs = TabPerformanceLogger.elapsedMillis(startNanos);
            LOG.info("[TabPerf] Restored history load finished in " + elapsedMs + "ms: " + tabDescriptor);
            if (!disposed) {
                callJavaScript("historyLoadComplete");
            }
        })).exceptionally(ex -> {
            long elapsedMs = TabPerformanceLogger.elapsedMillis(startNanos);
            LOG.warn("[TabPerf] Restored history load failed after " + elapsedMs + "ms: "
                    + tabDescriptor + ", error=" + ex.getMessage(), ex);
            LOG.warn("[TabRestore] Failed to load persisted tab history: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed) {
                    callJavaScript("historyLoadComplete");
                    callJavaScript("addErrorMessage",
                            JsUtils.escapeJs("Failed to restore session history: " + ex.getMessage()));
                }
            });
            return null;
        });
    }

    public void addCodeSnippetFromExternal(String selectionInfo) {
        addCodeSnippet(selectionInfo);
    }

    public void updateTabStatus(ChatWindowDelegate.TabAnswerStatus status) {
        chatWindowDelegate.updateTabStatus(status);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        chatWindowDelegate.sendQuickFixMessage(prompt, isQuickFix, callback);
    }

    public void executeJavaScriptCode(String jsCode) {
        if (this.disposed || this.browser == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!this.disposed && this.browser != null) {
                this.browser.getCefBrowser().executeJavaScript(jsCode, this.browser.getCefBrowser().getURL(), 0);
            }
        });
    }

    // ==================== JavaScript Bridge ====================

    /**
     * safe js function name.
     */
    private static final java.util.regex.Pattern SAFE_JS_FUNCTION_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$.]*$");

    /**
     * 下行总线(Java → 前端)的语义化入口。归一化重构(详见 plan: typed-booping-newt.md)。
     *
     * 后端调用语义化事件名 + payload,经前端 window.__bridge.dispatch(type, payloadJson) 单一入口
     * 派发到各业务模块的订阅者。Phase 0 双轨:内部仍走既有 callJavaScript 路径(包 typeof 检查与
     * try/catch),行为与旧 window.xxx 调用等价。后续 Phase 各 handler 由 callJavaScript("window.xxx")
     * 逐步迁移到本方法,旧 window.xxx 经前端 compat 兼容别名保留一阶段。
     *
     * @param type        事件类型(见前端 webview/src/bridge/events/)
     * @param payloadJson payload 的 JSON 字符串;可为 null/空(前端收到 undefined)
     */
    void dispatchEvent(String type, String payloadJson) {
        // 经既有 callJavaScript 路径派发到 window.__bridge.dispatch。传入全限定名,因含点,
        // callJavaScript 不会重复加 "window." 前缀(见其 contains(".") 判定);SAFE_JS_FUNCTION_NAME
        // 正则允许点号,故 "window.__bridge.dispatch" 通过校验。
        callJavaScript("window.__bridge.dispatch", type, payloadJson == null ? "" : payloadJson);
    }

    void callJavaScript(String functionName, String... args) {
        if (disposed || browser == null) {
            LOG.warn("Cannot call JS function " + functionName + ": disposed=" + disposed + ", browser=" + (browser == null ? "null" : "exists"));
            return;
        }

        if (functionName == null || !SAFE_JS_FUNCTION_NAME.matcher(functionName).matches()) {
            LOG.error("Invalid JavaScript function name rejected: " + functionName);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || browser == null) {
                return;
            }
            try {
                String callee = functionName;
                if (!functionName.contains(".")) {
                    callee = "window." + functionName;
                }

                StringBuilder argsJs = new StringBuilder();
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) { argsJs.append(", "); }
                        String arg = args[i] == null ? "" : args[i];
                        argsJs.append("'").append(arg).append("'");
                    }
                }

                String checkAndCall =
                        "(function() {" +
                                "  try {" +
                                "    if (typeof " + callee + " === 'function') {" +
                                "      " + callee + "(" + argsJs + ");" +
                                "    }" +
                                "  } catch (e) {" +
                                "    console.error('[Backend->Frontend] Failed to call " + functionName + ":', e);" +
                                "  }" +
                                "})();";

                browser.getCefBrowser().executeJavaScript(checkAndCall, browser.getCefBrowser().getURL(), 0);
            } catch (Exception e) {
                LOG.warn("Failed to call JS function: " + functionName + ", error: " + e.getMessage(), e);
            }
        });
    }

    void handleJavaScriptMessage(String message) {
        if (message.startsWith("{\"type\":\"console.")) {
            try {
                JsonObject json = GsonHolder.GSON.fromJson(message, JsonObject.class);
                String logType = json.get("type").getAsString();
                JsonArray args = json.getAsJsonArray("args");

                StringBuilder logMessage = new StringBuilder("[Webview] ");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) { logMessage.append(" "); }
                    logMessage.append(args.get(i).toString());
                }

                if ("console.error".equals(logType)) {
                    LOG.warn(logMessage.toString());
                } else if ("console.warn".equals(logType)) {
                    LOG.info(logMessage.toString());
                } else {
                    LOG.debug(logMessage.toString());
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse console log: " + e.getMessage());
            }
            return;
        }

        String type;
        String content;

        BridgeMessage bridgeMessage = parseBridgeMessage(message);
        if (bridgeMessage != null) {
            type = bridgeMessage.type();
            content = bridgeMessage.content();
        } else {
            String[] parts = message.split(":", 2);
            if (parts.length < 1) {
                LOG.error("Invalid message format");
                return;
            }
            type = parts[0];
            content = parts.length > 1 ? parts[1] : "";
        }

        // hide_panel:Shift+Esc 隐藏 CCG 面板。从 WebviewInitializer 的独立 hidePanelQuery 合并到主 sendToJava 路由,
        // 减少每个标签一个 JBCefJSQuery 实例。前置处理(在 SEND_MESSAGE 日志与 dispatcher 之前)。
        if ("hide_panel".equals(type)) {
            handleHidePanel();
            return;
        }

        if (UpstreamAction.SEND_MESSAGE.value().equals(type) || UpstreamAction.SEND_MESSAGE_WITH_ATTACHMENTS.value().equals(type)) {
            ClaudeSession currentSession = session;
            LOG.info(String.format(
                    "[CliConcurrencyDiag][Webview->Java] received %s: tab=%s, contentIndex=%d, sessionId=%s, channelId=%s, provider=%s, payloadChars=%d, thread=%s",
                    type, getCurrentTabName(), getTabIndex(),
                    currentSession != null ? currentSession.getSessionId() : "(none)",
                    currentSession != null ? currentSession.getChannelId() : "(none)",
                    currentSession != null ? currentSession.getProvider() : "(none)",
                    content.length(),
                    Thread.currentThread().getName()));
        }

        if (frontendActionDispatcher != null && frontendActionDispatcher.dispatch(type, content)) {
            return;
        }

        LOG.warn("Unknown message type: " + type);
    }

    /**
     * 隐藏 CCG 工具窗口(Shift+Esc)。逻辑从 WebviewInitializer 的 hidePanelQuery handler 搬移而来,
     * 保留原 project 存活守卫与 EDT 派发(ToolWindow 操作必须在 EDT)。
     */
    private void handleHidePanel() {
        try {
            if (project != null && !project.isDisposed()) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CCG");
                    if (toolWindow != null && toolWindow.isVisible()) {
                        toolWindow.hide();
                    }
                });
            }
        } catch (Exception ex) {
            LOG.warn("Failed to hide CCG panel via shortcut: " + ex.getMessage());
        }
    }

    private BridgeMessage parseBridgeMessage(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '{') {
            return null;
        }
        try {
            JsonObject json = GsonHolder.GSON.fromJson(message, JsonObject.class);
            if (json == null || !json.has("type")) {
                return null;
            }
            String type = json.get("type").getAsString();
            String content = json.has("content") && !json.get("content").isJsonNull()
                    ? json.get("content").getAsString()
                    : "";
            return new BridgeMessage(type, content);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record BridgeMessage(String type, String content) {
    }

    // ==================== Session Delegates ====================

    private void setupSessionCallbacks() {
        // Re-sync the exposed sessionId with the freshly bound session so a stale
        // AI session ID from a previous session is not exposed via getSessionId().
        // Falling back to permissionServiceKey (never null after construction)
        // keeps the exposed ID stable for consumers like DetachTabAction, which
        // skips DetachedWindowManager registration on a null ID.
        this.sessionId = resolveExposedSessionId(session.getSessionId(), this.permissionServiceKey);

        if (this.sessionCallbackAdapter != null) {
            this.sessionCallbackAdapter.dispose();
        }
        this.sessionCallbackAdapter = new SessionCallbackAdapter(
                streamCoalescer,
                new SessionCallbackAdapter.JsTarget() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        ClaudeChatWindow.this.callJavaScript(functionName, args);
                    }

                    @Override
                    public void dispatchEvent(String type, String payloadJson) {
                        HandlerContext currentContext = ClaudeChatWindow.this.handlerContext;
                        if (currentContext != null) {
                            currentContext.dispatchEvent(type, currentContext.escapeJs(payloadJson));
                        }
                    }                },
                permissionHandler,
                () -> slashCommandsFetched,
                this::onStreamCompleted,
                // 流式/思考区开关:按 projectPath 读 setting,默认 true(无 project 或读取出错走默认)。
                // 供应商人化:整 turn 快照值由 SessionCallbackAdapter.onStreamStart → TurnPushGate.onTurnStart 读取。
                () -> {
                    try {
                        String path = project.getBasePath();
                        return path == null || settingsService.getStreamingEnabled(path);
                    } catch (Exception e) {
                        return true;
                    }
                },
                () -> {
                    try {
                        String path = project.getBasePath();
                        return path == null || settingsService.getShowThinkingEnabled(path);
                    } catch (Exception e) {
                        return true;
                    }
                }
        ) {
            @Override
            public void onStreamStart() {
                ClaudeChatWindow.this.onSendStarted();
                super.onStreamStart();
            }

            @Override
            public void onSessionIdReceived(String newSessionId) {
                super.onSessionIdReceived(newSessionId);
                String provider = session != null ? session.getProvider() : handlerContext.getCurrentProvider();
                String runtimeEpoch = session != null ? session.getRuntimeSessionEpoch() : null;
                if (runtimeEpoch != null && !runtimeEpoch.isBlank()) {
                    AttachmentStorageService.getInstance()
                            .promotePendingSession(provider, "epoch-" + runtimeEpoch, newSessionId);
                }
                sessionId = newSessionId;
                persistTabSessionState();
            }

            @Override
            public void onQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState state, int aheadCount) {
                super.onQueueDisplayStateChanged(state, aheadCount);
                ChatWindowDelegate.TabAnswerStatus tabStatus;
                switch (state) {
                    case QUEUED:
                        tabStatus = ChatWindowDelegate.TabAnswerStatus.QUEUED;
                        break;
                    case PROCESSING:
                        tabStatus = ChatWindowDelegate.TabAnswerStatus.PROCESSING;
                        break;
                    case COMPLETED:
                        tabStatus = ChatWindowDelegate.TabAnswerStatus.COMPLETED;
                        break;
                    case NONE:
                    default:
                        tabStatus = ChatWindowDelegate.TabAnswerStatus.IDLE;
                        break;
                }
                chatWindowDelegate.updateTabStatus(tabStatus);
            }
        };
        session.setCallback(sessionCallbackAdapter);

        // Wire daemon events directly to frontend (bypasses adapter lifecycle).
        // Calling through sessionCallbackAdapter would silently drop the event
        // if setupSessionCallbacks() is invoked again before the title arrives
        // (adapter.deactivate() → isInactive() → event discarded).
        // Register only once per ClaudeChatWindow; subsequent setupSessionCallbacks()
        // calls reuse the existing listener so the bridge keeps a single registration
        // per window. The listener is removed in dispose().
        if (this.titleEventListener == null) {
            this.titleEventListener = (event, data) -> {
                if ("title_generated".equals(event)) {
                    String genSessionId = data.has("sessionId") ? data.get("sessionId").getAsString() : null;
                    String title = data.has("title") ? data.get("title").getAsString() : null;
                    if (genSessionId != null && title != null) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!disposed) {
                                // [归一化] updateSessionTitle(sessionId, title) 原为两参数,归一化为单 JSON {sessionId, title}
                                com.google.gson.JsonObject titlePayload = new com.google.gson.JsonObject();
                                titlePayload.addProperty("sessionId", genSessionId);
                                titlePayload.addProperty("title", title);
                                dispatchEvent(DownstreamEvent.SESSION_TITLE.value(), JsUtils.escapeJs(titlePayload.toString()));
                            }
                        });
                    }
                } else if ("session_updated".equals(event)) {
                    // Handle inter-turn session updates (background task completion)
                    String updatedSessionId = data.has("sessionId") ? data.get("sessionId").getAsString() : null;
                    if (updatedSessionId == null) {
                        LOG.warn("[ClaudeChatWindow] session_updated event missing sessionId");
                        return;
                    }

                    // Compare with current active session
                    String currentSessionId = session != null ? session.getSessionId() : null;
                    if (currentSessionId == null || !currentSessionId.equals(updatedSessionId)) {
                        // Event is for a different session, ignore
                        return;
                    }

                    // If a turn is streaming, DON'T reload now (clearMessages() off
                    // the EDT would race the streaming append and disturb the live
                    // bubble). DON'T drop it either, or a background-turn answer would
                    // stay invisible until the user reopens the session. Park the id
                    // and drain it at stream end (onStreamEnded).
                    if (sessionCallbackAdapter != null && streamCoalescer != null && streamCoalescer.isStreamActive()) {
                        deferredReload.defer(updatedSessionId);
                        // onStreamEnded drains this at the next stream-end. Also arm the
                        // safety backstop so a defer that races the stream-end edge — or
                        // the last fan-out answer with no following stream end — is still
                        // drained once the stream goes idle (see deferredReloadSafetyTick).
                        scheduleDeferredReloadSafetyDrain();
                        LOG.info("[ClaudeChatWindow] session_updated during active turn, deferring reload to stream end");
                        return;
                    }

                    LOG.info("[ClaudeChatWindow] session_updated for sessionId=" + updatedSessionId + ", reloading from server");

                    // Reuse the canonical reload path (same as history-load / rewind):
                    // loadFromServer() reads the session via the bridge, converts each
                    // record with MessageParser.parseServerMessage(), and pushes a full
                    // refresh through the callback facade. Coalesced so overlapping
                    // background-task completions never reload concurrently.
                    //
                    // Pass updatedSessionId as the reload target: the session field can
                    // be reassigned on the EDT (new-session / restart flows) between the
                    // currentSessionId check above and the reload actually running.
                    // driveSessionReload() re-validates the id at entry and after
                    // loadFromServer() returns, so a reload never lands on a session
                    // that the user has navigated away from.
                    requestSessionReload(updatedSessionId);
                }
            };
            this.claudeSDKBridge.addDaemonEventListener(this.titleEventListener);
        }

        persistTabSessionState();
    }

    /**
     * Request a reload of the current session from the server, coalescing
     * concurrent requests. Multiple session_updated events (e.g. several
     * background tasks finishing at once) must not run loadFromServer()
     * concurrently — SessionState's message list is not thread-safe and the
     * reload runs on a background thread. At most one reload is in flight;
     * requests arriving during a reload collapse into a single follow-up reload
     * that reflects the latest JSONL.
     *
     * @param targetSessionId the session id this reload is bound to. Carried
     *     through the whole coalesced chain and re-validated at every step so a
     *     reload never runs against a session the user has navigated away from
     *     (the session field is reassigned on the EDT by new-session / restart).
     */
    private void requestSessionReload(String targetSessionId) {
        synchronized (sessionReloadLock) {
            if (sessionReloadInFlight) {
                sessionReloadPending = true;
                return;
            }
            sessionReloadInFlight = true;
        }
        driveSessionReload(targetSessionId);
    }

    /**
     * Coordinates a session_updated reload that arrived while a turn was
     * streaming. Reloading mid-stream is unsafe: {@code loadFromServer()} runs
     * {@code clearMessages()} on SessionState off the EDT, which would race the
     * streaming append and disturb the live streaming bubble. So the target
     * session id is parked here and drained at stream end (onStreamEnded),
     * making background-turn answers appear at the next turn boundary instead of
     * only after the user reopens the session.
     *
     * <p>Thread-safety: {@code defer} is called from the daemon event thread,
     * {@code takeIfRunnable} from the coalescer's onStreamEnded hook; both are
     * fully synchronized so a defer/drain interleave never loses or duplicates a
     * pending reload. {@code take} atomically reads-clears-and-gates in one
     * critical section (no read/clear window). Coalescing is last-writer-wins:
     * overlapping background completions collapse into a single reload, which is
     * correct because a reload always reflects the latest JSONL. Extracted as a
     * static nested class so the coordination is unit-testable without a full
     * ClaudeChatWindow (which needs a Project, JBCefBrowser, etc.).
     */
    static final class DeferredReload {
        private String pendingSessionId;

        /** Park a reload for {@code sessionId} (last writer wins). */
        synchronized void defer(String sessionId) {
            this.pendingSessionId = sessionId;
        }

        /**
         * Atomically take-and-clear the parked reload, returning its target only
         * when it should actually run: something was deferred AND the window is
         * still alive. Returns {@code null} otherwise (and still clears, so a
         * stale parked id from a disposed window is not left behind). The target
         * is re-validated against the active session later in
         * driveSessionReload(), so this only gates the coarse "is there anything
         * to drain" question.
         */
        synchronized String takeIfRunnable(boolean disposed) {
            String target = pendingSessionId;
            pendingSessionId = null;
            return (target != null && !disposed) ? target : null;
        }

        /** Visible for testing: whether a reload is currently parked. */
        synchronized boolean hasPending() {
            return pendingSessionId != null;
        }
    }

    /**
     * Schedule a safety-backstop drain of any deferred reload. This ensures that
     * a deferred reload that races the stream-end edge — or the last fan-out answer
     * with no following stream end — is still drained once the stream goes idle.
     */
    private void scheduleDeferredReloadSafetyDrain() {
        deferredReloadSafetyAlarm.cancelAllRequests();
        deferredReloadSafetyAlarm.addRequest(() -> {
            if (disposed) return;
            if (streamCoalescer != null && !streamCoalescer.isStreamActive()) {
                String deferredId = deferredReload.takeIfRunnable(disposed);
                if (deferredId != null) {
                    driveSessionReload(deferredId);
                }
            }
        }, DEFERRED_RELOAD_SAFETY_DRAIN_MS);
    }

    private void driveSessionReload(String targetSessionId) {
        // Re-validate at entry: the session may have been replaced on the EDT
        // between the listener's sessionId check and this call.
        if (disposed || !isSessionActive(targetSessionId)) {
            synchronized (sessionReloadLock) {
                sessionReloadInFlight = false;
                sessionReloadPending = false;
            }
            return;
        }
        // A narrow window remains: the EDT can reassign `session` between the
        // isSessionActive() check above and the `current = session` read below,
        // so `current` may be a session the user has navigated away from. This is
        // safe by design: loadFromServer() pushes its result through `current`'s
        // own callbackFacade → SessionCallbackAdapter, and that adapter is
        // deactivated by setupSessionCallbacks() when the new session is bound
        // (volatile `active` flag, checked in every on* callback). So a stale
        // reload's onMessageUpdate/onStateChange are silently dropped, and the
        // isSessionActive() check in the continuation additionally blocks any
        // follow-up reload. Two independent guards; neither alone is sufficient.
        ClaudeSession current = session;
        current.loadFromServer().whenComplete((v, ex) -> {
            if (ex != null) {
                LOG.warn("[ClaudeChatWindow] session reload failed", ex);
            }
            boolean runAgain;
            synchronized (sessionReloadLock) {
                runAgain = decideReloadCompletion(
                        sessionReloadPending, disposed, isSessionActive(targetSessionId));
                // Always clear sessionReloadPending: on the runAgain path the
                // pending request is consumed; on the finish path any stale flag
                // (possibly bound to a session the user navigated away from) must
                // be dropped so the next same-session reload does not inherit it.
                sessionReloadPending = false;
                if (!runAgain) {
                    sessionReloadInFlight = false;
                }
            }
            if (runAgain) {
                driveSessionReload(targetSessionId);
            }
        });
    }

    /**
     * Pure decision function for what to do when an in-flight
     * {@code loadFromServer()} reload completes. Extracted so the coalescing
     * state machine is unit-testable without constructing a full
     * ClaudeChatWindow (which needs a Project, JBCefBrowser, etc.).
     *
     * <p>Returns {@code true} (run another reload) only when ALL of:
     * <ul>
     *   <li>a follow-up is pending ({@code sessionReloadPending}), AND</li>
     *   <li>the window is still alive ({@code !disposed}), AND</li>
     *   <li>the session the reload was started for is still active
     *       ({@code sessionMatches}). If the user navigated to a different
     *       session, the pending flag belongs to the old session and must not
     *       trigger a reload against the new one — the new session drives its
     *       own lifecycle.</li>
     * </ul>
     *
     * <p>Either way the caller clears {@code sessionReloadPending}; this
     * function only decides whether to re-run.
     *
     * @param pending        current value of {@code sessionReloadPending}
     * @param disposed       whether the window has been disposed
     * @param sessionMatches whether {@code session} still identifies the
     *                       session this reload was bound to
     * @return {@code true} to collapse the pending request into another reload;
     *         {@code false} to finish (the in-flight flag is cleared by the
     *         caller)
     */
    static boolean decideReloadCompletion(
            boolean pending, boolean disposed, boolean sessionMatches) {
        return pending && !disposed && sessionMatches;
    }

    /**
     * Returns true iff the window currently holds the session identified by
     * {@code sessionId} (i.e. it has not been replaced by a new-session /
     * restart flow on the EDT). The session field is volatile, so this read is
     * safe from the daemon-reader and loadFromServer() continuation threads.
     */
    private boolean isSessionActive(String sessionId) {
        ClaudeSession current = session;
        if (current == null || sessionId == null) {
            return false;
        }
        String currentId = current.getSessionId();
        return sessionId.equals(currentId);
    }

    private void onStreamCompleted() {
        ClaudeSession completedSession = session;
        if (!disposed && completedSession != null && historyRefreshService != null) {
            historyRefreshService.onStreamCompleted(completedSession.getProvider());
        }
        // 从流读取线程调用,而 notificationAlarm 是 SWING_THREAD Alarm,
        // cancelAllRequests/addRequest 必须在 EDT 执行,否则违反 Alarm 线程约束
        // (非 EDT 操作 SWING_THREAD Alarm 行为未定义,可能丢失请求或抛异常)。
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || session == null) {
                return;
            }
            notificationAlarm.cancelAllRequests();
            notificationAlarm.addRequest(this::maybeShowTaskCompletionNotification, 500);
        });
    }

    public void onSendStarted() {
        taskCompletionNotificationSent.set(false);
    }

    public void maybeShowTaskCompletionNotification() {
        if (disposed) {
            return;
        }
        if (!shouldShowTaskCompletionNotification(session)) {
            return;
        }
        if (!taskCompletionNotificationSent.compareAndSet(false, true)) {
            return;
        }
        com.github.claudecodegui.notifications.ClaudeNotifier.showTaskCompletionSuccess(
            project,
            com.github.claudecodegui.notifications.ClaudeNotifier.buildTitleFromSession(session),
            com.github.claudecodegui.notifications.ClaudeNotifier.buildPreviewFromSession(session,
                ClaudeCodeGuiBundle.message("notifier.taskComplete.title")));
    }

    static boolean shouldShowTaskCompletionNotification(ClaudeSession session) {
        if (session == null) {
            return false;
        }
        return shouldShowTaskCompletionNotification(session.getProvider(), session.getError());
    }

    static boolean shouldShowTaskCompletionNotification(String provider, String error) {
        if (error != null) {
            return false;
        }
        return provider != null && !provider.trim().isEmpty();
    }

    private void initializeSessionInfo() {
        String workingDirectory = sessionLifecycleManager.determineWorkingDirectory();
        session.setSessionInfo(null, workingDirectory);
        persistTabSessionState();
        LOG.info("Initialized with working directory: " + workingDirectory);
    }

    private void registerSessionLoadListener() {
        SessionLoadService.getInstance().setListener((sessionId, projectPath) -> {
            ApplicationManager.getApplication().invokeLater(() ->
                    sessionLifecycleManager.loadHistorySession(sessionId, projectPath));
        });
    }

    private void registerInstance() {
        ClaudeSDKToolWindow.registerWindow(project, this);
    }

    private void interruptDueToPermissionDenial() {
        this.session.interrupt().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onPermissionDenied");
            callJavaScript("onStreamEnd");
            callJavaScript("showLoading", "false");
            com.github.claudecodegui.notifications.ClaudeNotifier.clearStatus(project);
        }));
    }

    private int getTabIndex() {
        Content content = this.parentContent;
        if (content == null) {
            return -1;
        }
        ContentManager contentManager = content.getManager();
        if (contentManager == null) {
            return -1;
        }
        return contentManager.getIndexOfContent(content);
    }

    private void persistTabSessionState() {
        if (project == null || project.isDisposed() || session == null) {
            return;
        }

        int tabIndex = getTabIndex();
        if (tabIndex < 0) {
            return;
        }

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.provider = session.getProvider();
        snapshot.sessionId = session.getSessionId();
        snapshot.cwd = session.getCwd();
        snapshot.model = session.getModel();
        snapshot.permissionMode = session.getPermissionMode();
        snapshot.reasoningEffort = session.getReasoningEffort();
        snapshot.pinned = parentContent != null && ClaudeSDKToolWindow.isPinned(parentContent);

        TabStateService.getInstance(project).saveTabSessionState(tabIndex, snapshot);
        SessionRuntimeDefaults.rememberProvider(project, snapshot.provider);
        SessionRuntimeDefaults.rememberModel(project, snapshot.provider, snapshot.model);
    }

    private boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Decide what {@link #getSessionId()} exposes after session callbacks are
     * (re-)bound: the bound session's own ID when it has one (history load),
     * otherwise the stable permission-service key (fresh session) — never a
     * stale ID left over from a previously bound session.
     */
    static String resolveExposedSessionId(String boundSessionId, String permissionServiceKey) {
        return boundSessionId != null && !boundSessionId.trim().isEmpty()
                ? boundSessionId
                : permissionServiceKey;
    }

    // ==================== Code Snippets ====================

    private void addCodeSnippet(String selectionInfo) {
        if (selectionInfo != null && !selectionInfo.isEmpty()) {
            callJavaScript("addCodeSnippet", JsUtils.escapeJs(selectionInfo));
        }
    }

    public void focusInputPane() {
        if (disposed || browser == null) {
            return;
        }
        browser.getComponent().requestFocus();
        executeJavaScriptCode("window.focusChatInput?.()");
    }

    // ==================== Dispose ====================

    public synchronized void dispose() {
        if (this.disposed) { return; }
        long disposeStartNanos = System.nanoTime();
        String tabDescriptor = TabPerformanceLogger.describeTab(getCurrentTabName(),
                session != null ? session.getSessionId() : null);
        this.disposed = true;

        notificationAlarm.cancelAllRequests();
        chatWindowDelegate.dispose();
        editorContextTracker.dispose();
        streamCoalescer.dispose();
        deferredReloadSafetyAlarm.cancelAllRequests();
        Disposer.dispose(safetyAlarmDisposable);
        if (sessionCallbackAdapter != null) {
            sessionCallbackAdapter.dispose();
        }
        if (titleEventListener != null && claudeSDKBridge != null) {
            try {
                claudeSDKBridge.removeDaemonEventListener(titleEventListener);
            } catch (Exception e) {
                LOG.warn("Failed to remove daemon event listener: " + e.getMessage());
            }
            titleEventListener = null;
        }
        webviewWatchdog.stop();

        try {
            if (this.permissionServiceKey != null && !this.permissionServiceKey.isEmpty()) {
                PermissionService permissionService = PermissionService.getInstance(project, this.permissionServiceKey);
                permissionService.unregisterDialogShower(project);
                permissionService.unregisterAskUserQuestionDialogShower(project);
                permissionService.unregisterPlanApprovalDialogShower(project);
                PermissionService.removeInstance(this.permissionServiceKey);
                LOG.info("Removed PermissionService instance for key: " + this.permissionServiceKey);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister dialog showers or remove session instance: " + e.getMessage());
        }

        LOG.info("Starting window resource cleanup, project: " + project.getName());

        handlerContext.setDisposed(true);

        if (parentContent != null) {
            ClaudeSDKToolWindow.unregisterContentMapping(parentContent);
            LOG.debug("[MultiTab] Removed Content -> ClaudeChatWindow mapping during dispose");
        }

        ClaudeSDKToolWindow.unregisterWindow(project, this);

        try {
            long sessionDisposeStartNanos = System.nanoTime();
            if (session != null) { session.dispose(); }
            LOG.info("[TabPerf] Session dispose returned in "
                    + TabPerformanceLogger.elapsedMillis(sessionDisposeStartNanos) + "ms: " + tabDescriptor);
        } catch (Exception e) {
            LOG.warn("Failed to clean up session: " + e.getMessage());
        }

        boolean lastBridgeOwner = SharedBridgeReferenceCounter.release(project);
        if (lastBridgeOwner) {
            ProjectBridgeRegistry.remove(project);
            scheduleBridgeProcessCleanup(claudeSDKBridge, codexSDKBridge, project.getName());
        }

        try {
            if (browser != null) {
                long browserDisposeStartNanos = System.nanoTime();
                browser.dispose();
                LOG.info("[TabPerf] Browser dispose returned in "
                        + TabPerformanceLogger.elapsedMillis(browserDisposeStartNanos) + "ms: " + tabDescriptor);
                browser = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up browser: " + e.getMessage());
        }

        LOG.info("[TabPerf] ClaudeChatWindow.dispose returned in "
                + TabPerformanceLogger.elapsedMillis(disposeStartNanos) + "ms: " + tabDescriptor);
        LOG.info("Window resources fully cleaned up, project: " + project.getName());
    }

    private static void scheduleBridgeProcessCleanup(
            ClaudeSDKBridge claudeBridge,
            CodexSDKBridge codexBridge,
            String projectName
    ) {
        LOG.info("Scheduling async bridge process cleanup, project: " + projectName);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long cleanupStartNanos = System.nanoTime();
            LOG.info("Starting async bridge process cleanup, project: " + projectName);
            cleanupClaudeProcesses(claudeBridge);
            cleanupCodexProcesses(codexBridge);
            LOG.info("[TabPerf] Async bridge process cleanup finished in "
                    + TabPerformanceLogger.elapsedMillis(cleanupStartNanos) + "ms, project: " + projectName);
            LOG.info("Async bridge process cleanup finished, project: " + projectName);
        });
    }

    private static void cleanupClaudeProcesses(ClaudeSDKBridge claudeBridge) {
        if (claudeBridge == null) {
            return;
        }
        try {
            long cleanupStartNanos = System.nanoTime();
            int activeCount = claudeBridge.getActiveProcessCount();
            if (activeCount > 0) {
                LOG.info("Cleaning up " + activeCount + " active Claude process(es)...");
            }
            claudeBridge.cleanupAllProcesses();
            LOG.info("[TabPerf] Claude bridge cleanup returned in "
                    + TabPerformanceLogger.elapsedMillis(cleanupStartNanos) + "ms");
        } catch (Exception e) {
            LOG.warn("Failed to clean up Claude processes: " + e.getMessage());
        }
    }

    private static void cleanupCodexProcesses(CodexSDKBridge codexBridge) {
        if (codexBridge == null) {
            return;
        }
        try {
            long cleanupStartNanos = System.nanoTime();
            int activeCount = codexBridge.getActiveProcessCount();
            if (activeCount > 0) {
                LOG.info("Cleaning up " + activeCount + " active Codex process(es)...");
            }
            codexBridge.cleanupAllProcesses();
            LOG.info("[TabPerf] Codex bridge cleanup returned in "
                    + TabPerformanceLogger.elapsedMillis(cleanupStartNanos) + "ms");
        } catch (Exception e) {
            LOG.warn("Failed to clean up Codex processes: " + e.getMessage());
        }
    }

    String getCurrentTabName() {
        if (parentContent != null && parentContent.getDisplayName() != null && !parentContent.getDisplayName().trim().isEmpty()) {
            return parentContent.getDisplayName();
        }
        return originalTabName;
    }

    // ==================== Host Interface Factories ====================

    private WebviewInitializer.WebviewHost createWebviewHost() {
        return new WebviewInitializer.WebviewHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge getOpenCodeSDKBridge() {
                return openCodeSDKBridge;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public HtmlLoader getHtmlLoader() {
                return htmlLoader;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setBrowser(JBCefBrowser b) {
                browser = b;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public void handleJavaScriptMessage(String msg) {
                ClaudeChatWindow.this.handleJavaScriptMessage(msg);
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }
        };
    }

    private ChatWindowDelegate.DelegateHost createDelegateHost() {
        return new ChatWindowDelegate.DelegateHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge getOpenCodeSDKBridge() {
                return openCodeSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public CodemossSettingsService getSettingsService() {
                return settingsService;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public Content getParentContent() {
                return parentContent;
            }

            @Override
            public String getOriginalTabName() {
                return originalTabName;
            }

            @Override
            public void setOriginalTabName(String name) {
                ClaudeChatWindow.this.setOriginalTabName(name);
            }

            @Override
            public String getSessionId() {
                return sessionId;
            }

            @Override
            public boolean isActiveContent() {
                return ClaudeChatWindow.this.isActiveContent();
            }

            @Override
            public void activateContent() {
                ClaudeChatWindow.this.activateContent();
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public void setHandlerContext(HandlerContext ctx) {
                handlerContext = ctx;
            }

            @Override
            public void setFrontendActionDispatcher(FrontendActionDispatcher d) {
                frontendActionDispatcher = d;
            }

            @Override
            public void setPermissionHandler(PermissionActionHandlers h) {
                permissionHandler = h;
            }

            @Override
            public void setHistoryRefreshService(HistoryRefreshService service) {
                historyRefreshService = service;
            }

            @Override
            public SessionLifecycleManager getSessionLifecycleManager() {
                return sessionLifecycleManager;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public PermissionActionHandlers getPermissionHandler() {
                return permissionHandler;
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public void interruptDueToPermissionDenial() {
                ClaudeChatWindow.this.interruptDueToPermissionDenial();
            }

            @Override
            public boolean isFrontendReady() {
                return frontendReady;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                // No-op: count is reported but no longer consumed.
            }

            @Override
            public void persistTabSessionState() {
                ClaudeChatWindow.this.persistTabSessionState();
            }
        };
    }
}

