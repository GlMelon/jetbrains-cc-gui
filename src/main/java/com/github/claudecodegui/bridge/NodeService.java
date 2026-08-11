package com.github.claudecodegui.bridge;

import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.startup.BridgePreloader;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;

/**
 * Independent Node.js infrastructure service.
 * Provides Node.js detection, path management, and bridge directory access
 * without dependency on SDK Bridge classes.
 *
 * This service is designed to be used by Handler layer and other components
 * that need Node.js infrastructure without requiring SDK Bridge.
 */
public class NodeService {

    private static final Logger LOG = Logger.getInstance(NodeService.class);
    private static volatile NodeService instance;
    private static final Object lock = new Object();

    private final NodeDetector nodeDetector;
    private final ProcessManager processManager;
    private final EnvironmentConfigurator envConfigurator;

    /** Private constructor to enforce singleton pattern. */
    private NodeService() {
        this.nodeDetector = NodeDetector.getInstance();
        this.processManager = new ProcessManager();
        this.envConfigurator = new EnvironmentConfigurator();
        // Inject IntelliJ's managed executor into NodeDetector for in-flight detection tasks.
        this.nodeDetector.setDetectionExecutor(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Get the singleton instance of NodeService.
     */
    public static NodeService getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new NodeService();
                }
            }
        }
        return instance;
    }

    /**
     * Reset the singleton instance (for testing only).
     */
    @org.jetbrains.annotations.TestOnly
    public static void resetInstance() {
        synchronized (lock) {
            instance = null;
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
     * Get the ai-bridge directory (SDK test directory).
     * This is the directory containing channel-manager.js and other Node.js scripts.
     */
    public File getSdkTestDir() {
        return BridgePreloader.getSharedResolver().findSdkDir();
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
     * Clean up all active child processes.
     */
    public void cleanupAllProcesses() {
        processManager.cleanupAllProcesses();
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
