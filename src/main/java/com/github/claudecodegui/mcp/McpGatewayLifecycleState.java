package com.github.claudecodegui.mcp;

/** Lifecycle state of the Java-side project-scoped MCP Gateway facade. */
public enum McpGatewayLifecycleState {
    PROCESS_STARTING("process-starting"),
    IPC_READY("ipc-ready"),
    CATALOG_LOADING("catalog-loading"),
    READY("ready"),
    DEGRADED_DIRECT("degraded-direct"),
    FAILED("failed"),
    STOPPED("stopped");

    private final String value;

    McpGatewayLifecycleState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
