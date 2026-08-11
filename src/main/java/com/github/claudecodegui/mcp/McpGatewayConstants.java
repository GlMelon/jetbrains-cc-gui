package com.github.claudecodegui.mcp;

/**
 * MCP Gateway constants.
 * <p>
 * Keep Gateway protocol/config keys centralized so CLI wiring and Node control
 * API do not grow their own string tables.
 */
public final class McpGatewayConstants {
    private McpGatewayConstants() {
    }

    public static final String FEATURE_GATEWAY_ENABLED = "mcpGateway.enabled";
    public static final String FEATURE_CLI_ENABLED = "mcpGateway.cli.enabled";

    public static final String DIRECTORY_NAME = "mcp-gateway";
    public static final String CONFIG_DIRECTORY_NAME = "cli-gateway";
    public static final String STATE_FILE_NAME = "gateway-state.json";
    public static final String SERVER_SCRIPT_NAME = "mcp-gateway-server.js";
    public static final String STDIO_CLIENT_SCRIPT_PATH = "mcp-gateway/gateway-stdio-client.js";

    public static final String GATEWAY_SERVER_ID = "melon_gateway";

    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    public static final String KEY_REVISION = "revision";
    public static final String KEY_PROJECT_PATH = "projectPath";
    public static final String KEY_CONFIG_HASH = "configHash";
    public static final String KEY_SERVERS = "servers";
    public static final String KEY_SOURCE_PROVIDER = "sourceProvider";
    public static final String KEY_SERVER_ID = "serverId";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_TRANSPORT = "transport";
    public static final String KEY_CONFIG = "config";
    public static final String KEY_STATE_FILE = "stateFile";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_PORT = "port";
    public static final String KEY_PID = "pid";
    public static final String KEY_MCP_SERVERS = "mcpServers";
    public static final String KEY_MCP_SERVERS_CODEX = "mcp_servers";
    public static final String KEY_MCP_OPENCODE = "mcp";
    public static final String KEY_COMMAND = "command";
    public static final String KEY_ARGS = "args";
    public static final String KEY_TYPE = "type";
    public static final String KEY_ENV = "env";
    public static final String KEY_URL = "url";

    public static final String ARG_STATE_FILE = "--state-file";
    public static final String ARG_REVISION = "--revision";
    public static final String ARG_TOKEN = "--token";
    public static final String ARG_PROJECT_PATH = "--project-path";

    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_SSE = "sse";
}
