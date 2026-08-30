package com.github.claudecodegui.service;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Project-scoped facade that aggregates lifecycle and child-process diagnostics. */
@Service(Service.Level.PROJECT)
public final class ResourceDiagnosticsService {

    private final Project project;

    public ResourceDiagnosticsService(@NotNull Project project) {
        this.project = project;
    }

    public static ResourceDiagnosticsService getInstance(@NotNull Project project) {
        return project.getService(ResourceDiagnosticsService.class);
    }

    public RuntimeResourceDiagnostics snapshot(List<NodeProcessInfo> processes) {
        CliPersistentProcessRegistry.Diagnostics persistentRegistry =
                CliPersistentProcessRegistry.getInstance(project).diagnostics();
        McpGatewayService.Diagnostics gateway = McpGatewayService.getInstance(project).diagnostics();
        return RuntimeResourceDiagnostics.capture(processes, persistentRegistry, gateway);
    }
}
