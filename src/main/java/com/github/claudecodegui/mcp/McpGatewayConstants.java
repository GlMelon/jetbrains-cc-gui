package com.github.claudecodegui.mcp;

/**
 * MCP Gateway constants.
 * <p>
 * Keep Gateway protocol/config keys centralized so CLI wiring and Node control
 * API do not grow their own string tables.
 */
public final class McpGatewayConstants {
    public static final String EMPTY_JSON_OBJECT = "{}";
    private McpGatewayConstants() {
    }

    public static final String FEATURE_GATEWAY_ENABLED = "mcpGateway.enabled";
    public static final String FEATURE_CLI_ENABLED = "mcpGateway.cli.enabled";

    public static final String DIRECTORY_NAME = "mcp-gateway";
    public static final String CONFIG_DIRECTORY_NAME = "cli-gateway";
    public static final String STATE_FILE_NAME = "gateway-state.json";
    public static final String SERVER_SCRIPT_NAME = "mcp-gateway-server.js";
    /** CLI 直连 gateway 的 Streamable HTTP 端点路径(与 ai-bridge ipc-server.js 路由逐字对齐)。 */
    public static final String MCP_ENDPOINT_PATH = "/mcp";
    /** CLI 进程 env 中承载 gateway token 的变量名:三 provider 的 MCP 配置只写变量引用
     *  (Claude `${VAR}` / Codex bearer_token_env_var / OpenCode `{env:VAR}`),token 明文不进 argv/配置文件。 */
    public static final String ENV_GATEWAY_TOKEN = "MELON_MCP_GATEWAY_TOKEN";

    public static final String GATEWAY_SERVER_ID = "melon_gateway";

    /** 全局 MCP 配置(plugins 统一列表)来源标签。 */
    public static final String SOURCE_GLOBAL = "global";

    /** Gateway 聚合工具名契约(与 ai-bridge/mcp-gateway/tool-router.js parseGatewayToolName 逐字对齐):
     *  {@code mcp__<sourceProvider>__<serverId>__<toolName>}。 */
    public static final String GATEWAY_TOOL_PREFIX = "mcp__";
    public static final String GATEWAY_TOOL_SEPARATOR = "__";

    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    public static final String KEY_REVISION = "revision";
    public static final String KEY_PROJECT_PATH = "projectPath";
    public static final String KEY_CONFIG_HASH = "configHash";
    public static final String KEY_SERVERS = "servers";
    public static final String KEY_SOURCE_PROVIDER = "sourceProvider";
    public static final String KEY_SERVER_ID = "serverId";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_STATE = "state";
    public static final String KEY_LAST_ERROR = "lastError";
    public static final String KEY_LAST_SUCCESS_AT = "lastSuccessAt";
    public static final String KEY_FAILURE_COUNT = "failureCount";
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
    public static final String KEY_HEADERS = "headers";
    public static final String KEY_TIMEOUT = "timeout";
    /** ACP session/new mcpServers http 条目的 header 对象字段(ACP spec:{name,value} 数组)。 */
    public static final String KEY_NAME = "name";
    public static final String KEY_VALUE = "value";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    /** OpenCode remote MCP 条目的 type 值(opencode.json 官方契约,区别于 claude 的 {@link #TRANSPORT_HTTP})。 */
    public static final String OPENCODE_MCP_TYPE_REMOTE = "remote";
    /** OpenCode 原生配置的环境变量键(opencode.json mcp 字段用 {@code environment},区别于 Gateway 协议的 {@code env})。 */
    public static final String KEY_ENVIRONMENT_OPENCODE = "environment";

    public static final String ARG_STATE_FILE = "--state-file";
    public static final String ARG_TOKEN = "--token";
    public static final String ARG_PROJECT_PATH = "--project-path";

    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
    // ⚠️ transport/state 须保持字面量(下游有 switch-case 需要编译期常量);与
    // protocol.McpTransportType / McpGatewayState 的值一致性由 McpProtocolEnumSymmetryTest
    // 守门,state 值另与 ai-bridge server-supervisor.js 上报字面量逐字对齐。
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_SSE = "sse";

    public static final String STATE_READY = "READY";
    public static final String STATE_DEGRADED = "DEGRADED";
    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_BACKOFF = "BACKOFF";
    public static final String STATE_STOPPED = "STOPPED";
}
