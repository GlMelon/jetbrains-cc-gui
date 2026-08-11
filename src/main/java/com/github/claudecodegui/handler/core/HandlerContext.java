package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Handler context.
 * Provides all shared resources and callbacks needed by handlers.
 */
public class HandlerContext {

    public static final String DEFAULT_MODEL = CommonConstants.DEFAULT_MODEL;
    public static final String DEFAULT_PROVIDER = CommonConstants.DEFAULT_PROVIDER;

    private final Project project;
    private final CodemossSettingsService settingsService;
    private final JsCallback jsCallback;
    private volatile NodeService nodeService;
    private volatile FrontendReadyChecker frontendReadyChecker;

    // Mutable state accessed via getters/setters — volatile for thread safety
    private volatile ClaudeSession session;
    private volatile JBCefBrowser browser;
    private volatile String currentModel = DEFAULT_MODEL;
    private volatile String currentProvider = DEFAULT_PROVIDER;
    private volatile boolean disposed = false;
    private volatile Integer currentModelContextWindow;
    private volatile Runnable contentActivator;
    private volatile BooleanSupplier activeContentChecker;
    private volatile Supplier<String> contentTitleProvider;

    /**
     * JavaScript callback interface.
     */
    public interface JsCallback {
        void callJavaScript(String functionName, String... args);
        String escapeJs(String str);

        /**
         * 下行总线语义化入口(归一化重构)。Phase 0 双轨:内部走 window.__bridge.dispatch,
         * 经既有 callJavaScript 路径,行为等价于旧 window.xxx 调用。
         * 详见 plan: typed-booping-newt.md。
         */
        default void dispatchEvent(String type, String payloadJson) {
            callJavaScript("window.__bridge.dispatch", type, payloadJson == null ? "" : payloadJson);
        }
    }

    public HandlerContext(
            Project project,
            CodemossSettingsService settingsService,
            JsCallback jsCallback
    ) {
        this.project = project;
        this.settingsService = settingsService;
        this.jsCallback = jsCallback;
    }

    // Getters
    public Project getProject() {
        return project;
    }

    /**
     * Get the shared Node.js infrastructure service.
     * Provides Node.js detection, path management, bridge directory access,
     * and process management without dependency on SDK Bridge classes.
     */
    public NodeService getNodeService() {
        NodeService local = nodeService;
        if (local == null) {
            synchronized (this) {
                local = nodeService;
                if (local == null) {
                    local = NodeService.getInstance();
                    nodeService = local;
                }
            }
        }
        return local;
    }

    public CodemossSettingsService getSettingsService() {
        return settingsService;
    }

    /**
     * Resolve the normalized effective working directory for the current project —
     * the custom working directory when configured and valid, otherwise the project
     * base path. This is the directory Claude runs in and the key history is stored
     * under, so history readers must use this instead of the raw base path.
     *
     * <p>Null-safe: returns the raw base path when no settings service is wired.
     */
    public String resolveEffectiveWorkingDirectory() {
        String basePath = project != null ? project.getBasePath() : null;
        if (settingsService == null) {
            return basePath;
        }
        return settingsService.getEffectiveWorkingDirectory(basePath);
    }

    public ClaudeSession getSession() {
        return session;
    }

    public JBCefBrowser getBrowser() {
        return browser;
    }

    public String getCurrentModel() {
        return currentModel;
    }

    public String getCurrentProvider() {
        return currentProvider;
    }

    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Check if the frontend (webview) is ready to receive JavaScript calls.
     */
    public boolean isFrontendReady() {
        FrontendReadyChecker checker = this.frontendReadyChecker;
        return checker != null && checker.isFrontendReady();
    }

    /**
     * Set the frontend readiness checker.
     */
    public void setFrontendReadyChecker(FrontendReadyChecker checker) {
        this.frontendReadyChecker = checker;
    }

    /**
     * Interface for checking frontend readiness.
     */
    public interface FrontendReadyChecker {
        boolean isFrontendReady();
    }

    // Setters
    public void setSession(ClaudeSession session) {
        this.session = session;
    }

    public void setBrowser(JBCefBrowser browser) {
        this.browser = browser;
    }

    public void setCurrentModel(String currentModel) {
        this.currentModel = currentModel;
    }

    public void setCurrentProvider(String currentProvider) {
        this.currentProvider = currentProvider;
    }

    public Integer getCurrentModelContextWindow() {
        return currentModelContextWindow;
    }

    public void setCurrentModelContextWindow(Integer currentModelContextWindow) {
        this.currentModelContextWindow = currentModelContextWindow;
    }

    public void setDisposed(boolean disposed) {
        this.disposed = disposed;
    }

    public void setContentActivator(Runnable contentActivator) {
        this.contentActivator = contentActivator == null ? () -> { } : contentActivator;
    }

    public void setActiveContentChecker(BooleanSupplier checker) {
        this.activeContentChecker = checker;
    }

    public void setContentTitleProvider(Supplier<String> provider) {
        this.contentTitleProvider = provider;
    }

    public boolean isActiveContent() {
        BooleanSupplier checker = this.activeContentChecker;
        return checker != null && checker.getAsBoolean();
    }

    public String getContentTitle() {
        Supplier<String> provider = this.contentTitleProvider;
        return provider != null ? provider.get() : null;
    }

    public void activateContent() {
        Runnable activator = this.contentActivator;
        if (activator != null) {
            activator.run();
        }
    }

    // JavaScript callback proxy methods
    public void callJavaScript(String functionName, String... args) {
        jsCallback.callJavaScript(functionName, args);
    }

    /**
     * 下行总线语义化入口代理。Phase 0 双轨,详见 plan: typed-booping-newt.md。
     * 后续 Phase handler 由 callJavaScript("window.xxx") 迁移到本方法。
     */
    public void dispatchEvent(String type, String payloadJson) {
        jsCallback.dispatchEvent(type, payloadJson);
    }

    public String escapeJs(String str) {
        return jsCallback.escapeJs(str);
    }

    /**
     * Execute JavaScript on the EDT (Event Dispatch Thread).
     */
    public void executeJavaScriptOnEDT(String jsCode) {
        JBCefBrowser targetBrowser = this.browser;
        if (targetBrowser == null || this.disposed) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (this.disposed || this.browser != targetBrowser) {
                return;
            }
            try {
                org.cef.browser.CefBrowser cefBrowser = targetBrowser.getCefBrowser();
                cefBrowser.executeJavaScript(jsCode, cefBrowser.getURL(), 0);
            } catch (Exception | LinkageError ignored) {
                // The webview may be disposed between the generation check and execution.
            }
        });
    }
}
