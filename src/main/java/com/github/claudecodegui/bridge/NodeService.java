package com.github.claudecodegui.bridge;

import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.startup.BridgePreloader;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.TestOnly;

import java.io.File;

/**
 * Independent Node.js infrastructure service.
 * Provides Node.js detection, path management, and bridge directory access.
 *
 * This service is designed to be used by the Handler layer and other components
 * that need Node.js infrastructure.
 */
public class NodeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(NodeService.class);
    /**
     * Fallback instance: only used before the platform service is resolvable
     * (very early bootstrap / isolated unit tests). Mirrors {@code CliSessionExecutor}'s
     * FALLBACK pattern. The platform-owned service (resolved via getService) is the
     * authoritative singleton at runtime; this field only holds the fallback.
     */
    private static volatile NodeService fallbackInstance;
    private static final Object lock = new Object();

    private final NodeDetector nodeDetector;
    private final ProcessManager processManager;
    private final EnvironmentConfigurator envConfigurator;
    private boolean fallbackOwnerRegistered;
    private volatile boolean disposed;

    /**
     * Public no-arg constructor: required for platform {@code applicationService}
     * registration (see plugin.xml). Mirrors {@code CliSessionExecutor} /
     * {@code ConfigFileWatcherService}.
     */
    public NodeService() {
        this(false);
    }

    private NodeService(boolean fallback) {
        this.nodeDetector = NodeDetector.getInstance();
        this.processManager = new ProcessManager();
        this.envConfigurator = fallback
                ? new EnvironmentConfigurator(new CodemossSettingsService())
                : new EnvironmentConfigurator();
        // Inject IntelliJ's managed executor into NodeDetector for in-flight detection tasks.
        this.nodeDetector.setDetectionExecutor(AppExecutorUtil.getAppExecutorService());
        if (fallback) {
            LOG.warn("[NodeService] Platform service unavailable; starting fallback NodeService "
                    + "with a long-lived stale-channel sweeper. Cleanup depends on resetInstance() "
                    + "or the registered Disposable owner.");
        }
        // Start the ledger watchdog: reaps channel processes whose owning thread died
        // without unregistering (previously dead code — see cleanupStaleChannelProcesses).
        // Cancelled by ProcessManager.cleanupAllProcesses() in dispose().
        this.processManager.startStaleChannelSweeper();
    }

    /**
     * Resolve the shared NodeService. Prefers the platform-managed application service
     * (auto-disposed on plugin unload / IDE shutdown — the authoritative cleanup hook
     * for child processes); falls back to a lazily created instance when the application
     * is not yet resolvable (early bootstrap / isolated unit tests), mirroring
     * {@code CliSessionExecutor.sharedExecutor()}'s try/catch fallback.
     */
    public static NodeService getInstance() {
        Application application = null;
        try {
            application = ApplicationManager.getApplication();
            if (application != null) {
                NodeService service = application.getService(NodeService.class);
                if (service != null) {
                    disposeFallbackIfPresent();
                    return service;
                }
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager unavailable (isolated tests / plugin bootstrap).
        }
        return getOrCreateFallback(application);
    }

    private static NodeService getOrCreateFallback(Disposable owner) {
        synchronized (lock) {
            NodeService fallback = fallbackInstance;
            if (fallback == null) {
                fallback = new NodeService(true);
                // Publish before registering: if the owner is concurrently disposed,
                // NodeService.dispose() can still clear the authoritative fallback reference.
                fallbackInstance = fallback;
            }
            registerFallbackOwnerIfAvailable(fallback, owner);
            return fallback;
        }
    }

    private static void registerFallbackOwnerIfAvailable(NodeService fallback, Disposable owner) {
        if (fallback.fallbackOwnerRegistered) {
            return;
        }
        if (owner == null) {
            LOG.warn("[NodeService] No Disposable owner is available for the fallback; "
                    + "resetInstance() must release its processes and sweeper.");
            return;
        }
        try {
            Disposer.register(owner, fallback);
            fallback.fallbackOwnerRegistered = true;
            LOG.warn("[NodeService] Registered fallback NodeService under the available "
                    + "application Disposable owner.");
        } catch (RuntimeException e) {
            LOG.warn("[NodeService] Failed to register fallback Disposable owner: "
                    + e.getMessage(), e);
        }
    }

    private static void disposeFallbackIfPresent() {
        synchronized (lock) {
            NodeService fallback = fallbackInstance;
            if (fallback != null) {
                LOG.warn("[NodeService] Platform service became available; disposing the bootstrap fallback.");
                Disposer.dispose(fallback);
            }
        }
    }

    private static void clearFallbackReference(NodeService instance) {
        synchronized (lock) {
            if (fallbackInstance == instance) {
                fallbackInstance = null;
            }
        }
    }

    /**
     * Reset the fallback singleton (testing only). The platform service is managed by
     * the container and cannot be reset here; only the hand-rolled fallback instance
     * (used when getService is unavailable) is disposed and cleared.
     */
    @TestOnly
    public static void resetInstance() {
        synchronized (lock) {
            NodeService fallback = fallbackInstance;
            if (fallback != null) {
                // dispose() owns clearing fallbackInstance, so cleanup always runs first.
                Disposer.dispose(fallback);
            }
        }
    }

    @TestOnly
    static NodeService getFallbackInstanceForTest(Disposable owner) {
        return getOrCreateFallback(owner);
    }

    @TestOnly
    static boolean hasFallbackInstanceForTest() {
        return fallbackInstance != null;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        clearFallbackReference(this);
        // Authoritative cleanup on plugin unload / IDE shutdown — the single path
        // that reaps every child process registered with the ProcessManager.
        // cleanupAllProcesses itself is idempotent.
        try {
            processManager.cleanupAllProcesses();
        } catch (Exception e) {
            LOG.warn("[NodeService] Error during dispose cleanup: " + e.getMessage(), e);
        }
    }

    // ============================================================================
    // Node.js Detection
    // ============================================================================

    /**
     * Get the current Node.js executable path.
     */
    public String getNodeExecutable() {
        return nodeDetector.getNodeExecutable();
    }

    /**
     * Get the shared NodeDetector instance.
     */
    public NodeDetector getNodeDetector() {
        return nodeDetector;
    }

    /**
     * Set Node.js executable path manually.
     */
    public void setNodeExecutable(String path) {
        nodeDetector.setNodeExecutable(path);
    }

    /**
     * Detect Node.js with detailed result.
     */
    public NodeDetectionResult detectNodeWithDetails() {
        return nodeDetector.detectNodeWithDetails();
    }

    /**
     * Verify and cache Node.js path.
     */
    public NodeDetectionResult verifyAndCacheNodePath(String path) {
        return nodeDetector.verifyAndCacheNodePath(path);
    }

    /**
     * Get cached Node.js version.
     */
    public String getCachedNodeVersion() {
        return nodeDetector.getCachedNodeVersion();
    }

    /**
     * Get cached Node.js path.
     */
    public String getCachedNodePath() {
        return nodeDetector.getCachedNodePath();
    }

    // ============================================================================
    // Bridge Directory
    // ============================================================================

    /**
     * Get the ai-bridge directory.
     * This is the directory containing channel-manager.js and other Node.js scripts.
     */
    public File getBridgeDir() {
        return BridgePreloader.getSharedResolver().findBridgeDir();
    }

    /**
     * Get the BridgeDirectoryResolver for advanced directory operations.
     */
    public BridgeDirectoryResolver getDirectoryResolver() {
        return BridgePreloader.getSharedResolver();
    }

    // ============================================================================
    // Process Management
    // ============================================================================

    /**
     * Get the ProcessManager instance.
     */
    public ProcessManager getProcessManager() {
        return processManager;
    }

    /**
     * Get the count of active processes.
     */
    public int getActiveProcessCount() {
        return processManager.getActiveProcessCount();
    }

    // ============================================================================
    // Environment Configuration
    // ============================================================================

    /**
     * Get the EnvironmentConfigurator instance.
     */
    public EnvironmentConfigurator getEnvConfigurator() {
        return envConfigurator;
    }

    /**
     * Get the session ID.
     */
    public String getSessionId() {
        return envConfigurator.getSessionId();
    }

    /**
     * Set the session ID.
     */
    public void setSessionId(String sessionId) {
        envConfigurator.setSessionId(sessionId);
    }
}
