package com.github.claudecodegui.service;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Service to monitor Run/Debug service output.
 * This service attaches listeners to all active and new Run configurations.
 *
 * <h3>Implementation Notes</h3>
 * <p>This service uses IntelliJ platform APIs to monitor run configurations:</p>
 * <ul>
 *   <li>{@link ExecutionManager} - Listens for execution events (process started/terminated)</li>
 *   <li>{@link RunContentManager} - Accesses run content descriptors</li>
 *   <li>{@link ProcessHandler} - Captures process output</li>
 * </ul>
 *
 * <p>Unlike TerminalMonitorService which monitors terminal widgets,
 * this service monitors Run/Debug configurations (e.g., Spring Boot services,
 * application runs, Gradle/Maven tasks, etc.)</p>
 *
 * <h3>Optimization Notes</h3>
 * <p>This implementation optimizes by:</p>
 * <ul>
 *   <li>Using {@link ExecutionManager} to listen for execution events instead of ToolWindow polling</li>
 *   <li>Using {@link RunContentManager} to access run content descriptors</li>
 *   <li>Minimizing reflection usage by preferring public platform APIs</li>
 * </ul>
 */
public class RunConfigMonitorService implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(RunConfigMonitorService.class);

    /**
     * Disposable that owns the MessageBusConnection and all listeners
     * registered by this service. It is registered with the project so that, even if
     * no explicit dispose() runs, project disposal still releases every listener
     * (preventing the listener accumulation that previously depended on tool-window
     * teardown alone).
     */
    private final Disposable parentDisposable = Disposer.newDisposable("RunConfigMonitorService");

    /**
     * Buffer storage for run configuration output using WeakHashMap.
     * Buffers are automatically cleaned up when the associated descriptor is garbage collected.
     */
    private static final Map<RunContentDescriptor, StringBuilder> buffers =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Set of monitored process handlers using WeakHashMap to prevent memory leaks.
     * When a handler is garbage collected, it will be automatically removed from this set.
     */
    private static final Set<ProcessHandler> monitoredHandlers =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final int MAX_BUFFER_SIZE = 100000; // Keep last 100k chars

    private Project currentProject;

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        this.currentProject = project;
        // Bind parentDisposable to the project so every listener is released when the
        // project closes, regardless of whether an explicit dispose() runs.
        try {
            Disposer.register(project, parentDisposable);
        } catch (Exception alreadyRegistered) {
            // Already bound (defensive against duplicate execute); safe to ignore.
        }
        ApplicationManager.getApplication().invokeLater(() -> monitorRunConfigurations(project));
        return Unit.INSTANCE;
    }

    private void monitorRunConfigurations(@NotNull Project project) {
        // Use ExecutionManager to listen for execution events
        // This is more efficient than polling ToolWindow changes
        try {
            ExecutionManager executionManager = ExecutionManager.getInstance(project);
            
            // Subscribe to execution events via MessageBus
            // ExecutionManager.EXECUTION_TOPIC provides events when processes start/terminate
            project.getMessageBus().connect(parentDisposable)
                    .subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionManager.ExecutionListener() {
                        @Override
                        public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
                            LOG.debug("Process started: " + env.getRunProfile().getName());
                            // Delay attachment to allow RunContentDescriptor to be fully initialized
                            com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                                .schedule(() -> ApplicationManager.getApplication().invokeLater(() -> {
                                    if (currentProject != null && !currentProject.isDisposed()) {
                                        attachToExistingDescriptors(currentProject);
                                    }
                                }), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        }

                        @Override
                        public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
                            LOG.debug("Process terminated: " + env.getRunProfile().getName());
                        }
                    });
            
            LOG.debug("ExecutionManager listener attached for project: " + project.getName());
        } catch (Exception e) {
            LOG.warn("Failed to attach ExecutionManager listener, falling back to ToolWindow polling", e);
            // Fallback to ToolWindow polling if ExecutionManager is not available
            monitorRunConfigurationsViaToolWindow(project);
        }

        // Also attach to existing descriptors
        attachToExistingDescriptors(project);
    }

    /**
     * Fallback method: Monitor run configurations via ToolWindow polling.
     * Used when ExecutionManager is not available.
     */
    private void monitorRunConfigurationsViaToolWindow(@NotNull Project project) {
        // Listen for Run ToolWindow changes
        project.getMessageBus().connect(parentDisposable)
                .subscribe(com.intellij.openapi.wm.ToolWindowManagerListener.TOPIC, 
                    new com.intellij.openapi.wm.ex.ToolWindowManagerListener() {
            @Override
            public void stateChanged(@NotNull com.intellij.openapi.wm.ToolWindowManager toolWindowManager) {
                attachToExistingDescriptors(project);
            }
        });
    }

    private void attachToExistingDescriptors(@NotNull Project project) {
        try {
            RunContentManager runContentManager = RunContentManager.getInstance(project);
            if (runContentManager == null) {
                LOG.debug("RunContentManager is null");
                return;
            }

            List<RunContentDescriptor> descriptors = runContentManager.getAllDescriptors();
            LOG.debug("Found " + descriptors.size() + " run descriptors");
            
            for (RunContentDescriptor descriptor : descriptors) {
                attachToDescriptor(descriptor);
            }
        } catch (Exception e) {
            LOG.error("Error attaching to existing descriptors", e);
        }
    }

    private void attachToDescriptor(@NotNull RunContentDescriptor descriptor) {
        ProcessHandler processHandler = descriptor.getProcessHandler();
        if (processHandler == null) {
            LOG.debug("ProcessHandler is null for: " + descriptor.getDisplayName());
            return;
        }

        if (monitoredHandlers.contains(processHandler)) {
            return;
        }

        monitoredHandlers.add(processHandler);
        String displayName = descriptor.getDisplayName();
        LOG.debug("Monitoring run configuration: " + displayName);

        // Initialize buffer for this descriptor
        buffers.computeIfAbsent(descriptor, k -> new StringBuilder());

        // Attach process listener to capture output
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText();
                if (text != null && !text.isEmpty()) {
                    StringBuilder sb = buffers.computeIfAbsent(descriptor, k -> new StringBuilder());
                    synchronized (sb) {
                        sb.append(text);
                        if (sb.length() > MAX_BUFFER_SIZE) {
                            sb.delete(0, sb.length() - MAX_BUFFER_SIZE);
                        }
                    }
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                LOG.debug("Process terminated: " + displayName + " with exit code: " + event.getExitCode());
            }
        });

        // Handle disposal
        if (descriptor.getProcessHandler() != null) {
            processHandler.addProcessListener(new ProcessListener() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    // Clean up after a delay to allow final output to be captured
                    com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                        .schedule(() -> {
                            monitoredHandlers.remove(processHandler);
                            // Keep buffer for a while in case user wants to read it
                        }, 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            });
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Get all active run configuration descriptors for a project.
     */
    public static List<RunConfigInfo> getRunConfigurations(@NotNull Project project) {
        List<RunConfigInfo> configs = new ArrayList<>();
        try {
            RunContentManager runContentManager = RunContentManager.getInstance(project);
            if (runContentManager == null) { return configs; }

            List<RunContentDescriptor> descriptors = runContentManager.getAllDescriptors();
            for (RunContentDescriptor descriptor : descriptors) {
                ProcessHandler handler = descriptor.getProcessHandler();
                boolean isRunning = handler != null && !handler.isProcessTerminated();
                
                configs.add(new RunConfigInfo(
                    descriptor,
                    descriptor.getDisplayName(),
                    isRunning,
                    handler != null ? System.identityHashCode(handler) : -1
                ));
            }
        } catch (Exception e) {
            LOG.error("Error getting run configurations", e);
        }
        return configs;
    }

    /**
     * Get the captured output content of a run configuration.
     */
    public static String getRunConfigContent(@NotNull RunContentDescriptor descriptor) {
        StringBuilder sb = buffers.get(descriptor);
        String captured = "";
        if (sb != null) {
            synchronized (sb) {
                captured = sb.toString();
            }
        }
        
        LOG.debug("getRunConfigContent for " + descriptor.getDisplayName() + ", captured length: " + captured.length());

        // Try to get content from ConsoleView if captured is empty
        if (captured.isEmpty()) {
            captured = getConsoleContent(descriptor);
        }

        return captured;
    }

    /**
     * Get content directly from ConsoleView (fallback method).
     */
    private static String getConsoleContent(@NotNull RunContentDescriptor descriptor) {
        try {
            Object console = descriptor.getExecutionConsole();
            if (console == null) {
                LOG.debug("ExecutionConsole is null for: " + descriptor.getDisplayName());
                return "";
            }

            // Try multiple approaches to get console text
            String[] methodNames = {"getText", "getComponent"};
            
            // Approach 1: If it's a ConsoleViewImpl, try getText directly
            try {
                Method getTextMethod = console.getClass().getMethod("getText");
                Object result = getTextMethod.invoke(console);
                if (result instanceof String) {
                    LOG.debug("Got console text via getText() method");
                    return (String) result;
                }
            } catch (NoSuchMethodException e) {
                // Not a ConsoleViewImpl, try other approaches
            }

            // Approach 2: Try to get the editor and read its document
            try {
                Method getEditorMethod = console.getClass().getMethod("getEditor");
                Object editor = getEditorMethod.invoke(console);
                if (editor != null) {
                    Method getDocumentMethod = editor.getClass().getMethod("getDocument");
                    Object document = getDocumentMethod.invoke(editor);
                    if (document != null) {
                        Method getTextMethod = document.getClass().getMethod("getText");
                        Object text = getTextMethod.invoke(document);
                        if (text instanceof String) {
                            LOG.debug("Got console text via editor.getDocument().getText()");
                            return (String) text;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("Could not get text from editor: " + e.getMessage());
            }

            LOG.debug("Could not extract text from console: " + console.getClass().getName());
        } catch (Exception e) {
            LOG.error("Error getting console content", e);
        }
        return "";
    }

    /**
     * Data class for run configuration info.
     */
    public static class RunConfigInfo {
        private final RunContentDescriptor descriptor;
        private final String displayName;
        private final boolean running;
        private final int processId;

        public RunConfigInfo(RunContentDescriptor descriptor, String displayName, boolean running, int processId) {
            this.descriptor = descriptor;
            this.displayName = displayName;
            this.running = running;
            this.processId = processId;
        }

        public RunContentDescriptor getDescriptor() {
            return descriptor;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isRunning() {
            return running;
        }

        public int getProcessId() {
            return processId;
        }

        /**
         * Get the captured output of this run configuration.
         */
        public String getContent() {
            return RunConfigMonitorService.getRunConfigContent(descriptor);
        }
    }
}
