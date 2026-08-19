package com.github.claudecodegui.protocol;

/**
 * 下行协议事件(后端 → 前端)的唯一权威定义(SSOT)。
 * 前端 TypeScript 常量由此枚举在构建时自动生成。
 *
 * <p>命名规则:枚举常量为 UPPER_SNAKE_CASE,协议值为 dot.notation。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum DownstreamEvent implements ProtocolValue {

    // ── Webview Bootstrap ──
    WEBVIEW_BOOTSTRAP("webview.bootstrap"),

    // ── Usage/Settings ──
    USAGE_UPDATE("usage.update"),
    USAGE_STATISTICS("usage.statistics"),
    SETTING_STREAMING_ENABLED("setting.streaming_enabled"),
    SETTING_SHOW_THINKING_ENABLED("setting.show_thinking_enabled"),
    SETTING_SEND_SHORTCUT("setting.send_shortcut"),
    SETTING_AUTO_OPEN_FILE("setting.auto_open_file"),
    SETTING_THINKING_ENABLED("setting.thinking_enabled"),
    SETTING_PERMISSION_DIALOG_TIMEOUT("setting.permission_dialog_timeout"),

    // ── Mode/Model ──
    MODE_RECEIVED("mode.received"),
    MODEL_CONFIRMED("model.confirmed"),
    MODEL_SELECTION("model.selection"),

    // ── Provider ──
    PROVIDER_LIST("provider.list"),
    PROVIDER_ACTIVE("provider.active"),
    PROVIDER_CODEX_LIST("provider.codex_list"),
    PROVIDER_ACTIVE_CODEX("provider.active_codex"),
    PROVIDER_OPENCODE_LIST("provider.opencode_list"),
    PROVIDER_ACTIVE_OPENCODE("provider.active_opencode"),
    PROVIDER_CLAUDE_CONFIG("provider.claude_config"),
    PROVIDER_CODEX_CONFIG("provider.codex_config"),
    PROVIDER_OPENCODE_CONFIG("provider.opencode_config"),
    PROVIDER_CLI_LOGIN_ACCOUNT("provider.cli_login_account"),
    PROVIDER_IMPORT_PREVIEW("provider.import_preview"),

    // ── Session ──
    SESSION_RUNTIME_STATE("session.runtime_state"),
    SESSION_TITLE("session.title"),
    REWIND_RESULT("rewind.result"),
    SESSION_CAPABILITIES("session.capabilities"),

    // ── RPC ──
    // 请求/响应两侧归一化:请求用上行 action resolve_file_path(UpstreamAction.RESOLVE_FILE_PATH),
    // 响应用下行事件 file_path.resolved。早先的 'file_path.resolve' 下行伪事件已删除(它是
    // 误放进下行枚举的上行请求名,曾诱导前端把它当作请求 type 导致后端 dispatcher miss)。
    FILE_PATH_RESOLVED("file_path.resolved"),
    // 模型拉取 RPC 响应(对称 fetch_provider_models 上行;携带 __requestId 供 hub 路由 Promise)
    PROVIDER_MODELS_FETCHED("provider.models_fetched"),

    // ── Streaming ──
    STREAM_START("stream.start"),
    STREAM_CONTENT_DELTA("stream.content_delta"),
    STREAM_THINKING_DELTA("stream.thinking_delta"),
    STREAM_RESPONSE_PHASE("stream.response_phase"),
    STREAM_END("stream.end"),
    STREAM_HEARTBEAT("stream.heartbeat"),
    STREAM_PERMISSION_DENIED("stream.permission_denied"),
    STREAM_BLOCK_RESET("stream.block_reset"),

    // ── Font ──
    FONT_APPLY_EDITOR("font.apply_editor"),
    FONT_APPLY_UI("font.apply_ui"),
    FONT_APPLY_CODE("font.apply_code"),
    FONT_EDITOR_CONFIG_RECEIVED("font.editor_config_received"),
    FONT_UI_CONFIG_RECEIVED("font.ui_config_received"),
    FONT_CODE_CONFIG_RECEIVED("font.code_config_received"),

    // ── Theme/Language/Appearance ──
    THEME_RECEIVED("theme.received"),
    THEME_CHANGED("theme.changed"),
    LANGUAGE_APPLY("language.apply"),
    LANGUAGE_USER_LANGUAGE("language.user_language"),
    APPEARANCE_APPLY("appearance.apply"),
    AVATAR_CONFIG_APPLY("avatar.config_apply"),

    // ── Linkify ──
    LINKIFY_UPDATE("linkify.update"),

    // ── Context ──
    CONTEXT_ACTION("context.action"),

    // ── Dialog ──
    DIALOG_PERMISSION("dialog.permission"),
    DIALOG_ASK_USER_QUESTION("dialog.ask_user_question"),
    DIALOG_PLAN_APPROVAL("dialog.plan_approval"),

    // ── Toast ──
    TOAST_ERROR("toast.error"),
    TOAST_SUCCESS("toast.success"),
    TOAST_SUCCESS_I18N("toast.success_i18n"),
    TOAST_SWITCH_SUCCESS("toast.switch_success"),

    // ── Config/Settings ──
    CONFIG_WORKING_DIRECTORY("config.working_directory"),
    CONFIG_CODEX_SANDBOX_MODE("config.codex_sandbox_mode"),
    CONFIG_COMMIT_PROMPT("config.commit_prompt"),
    CONFIG_PROMPT_ENHANCER("config.prompt_enhancer"),
    CONFIG_COMMIT_AI("config.commit_ai"),
    CONFIG_PROJECT_COMMIT_PROMPT("config.project_commit_prompt"),
    CONFIG_COMMIT_GENERATION("config.commit_generation"),
    CONFIG_AI_TITLE_GENERATION("config.ai_title_generation"),
    CONFIG_STATUS_BAR_WIDGET("config.status_bar_widget"),
    CONFIG_TASK_COMPLETION_NOTIFICATION("config.task_completion_notification"),
    CONFIG_ASK_USER_QUESTION_NOTIFICATION("config.ask_user_question_notification"),
    CONFIG_MCP_GATEWAY("config.mcp_gateway"),
    CONFIG_CLI_PERSISTENT("config.cli_persistent"),
    CONFIG_SMITHERY_API_KEY("config.smithery_api_key"),

    // ── Model Registry ──
    MODEL_REGISTRY("model_registry"),
    MODEL_REGISTRY_UPDATED("model_registry_updated"),
    MODEL_REGISTRY_SCHEMA("model_registry_schema"),

    // ── Agent ──
    AGENT_LIST("agent.list"),
    AGENT_OPERATION_RESULT("agent.operation_result"),
    AGENT_SELECTED_CHANGED("agent.selected_changed"),
    AGENT_SELECTED_RECEIVED("agent.selected_received"),
    AGENT_IMPORT_PREVIEW("agent.import_preview"),
    AGENT_IMPORT_RESULT("agent.import_result"),

    // ── MCP Server (Claude) ──
    MCP_SERVER_LIST("mcp.server_list"),
    MCP_SERVER_STATUS("mcp.server_status"),
    MCP_SERVER_TOOLS("mcp.server_tools"),
    MCP_SERVER_ADDED("mcp.server_added"),
    MCP_SERVER_UPDATED("mcp.server_updated"),
    MCP_SERVER_DELETED("mcp.server_deleted"),
    MCP_SERVER_TOGGLED("mcp.server_toggled"),
    MCP_SERVER_VALIDATED("mcp.server_validated"),
    MCP_GATEWAY_STATUS("mcp.gateway.status"),

    // ── MCP Server (Codex) ──
    CODEX_MCP_SERVER_LIST("codex.mcp.server_list"),
    CODEX_MCP_SERVER_STATUS("codex.mcp.server_status"),
    CODEX_MCP_SERVER_TOOLS("codex.mcp.server_tools"),
    CODEX_MCP_SERVER_ADDED("codex.mcp.server_added"),
    CODEX_MCP_SERVER_UPDATED("codex.mcp.server_updated"),
    CODEX_MCP_SERVER_DELETED("codex.mcp.server_deleted"),
    CODEX_MCP_SERVER_TOGGLED("codex.mcp.server_toggled"),
    CODEX_MCP_SERVER_VALIDATED("codex.mcp.server_validated"),

    // ── MCP Server (OpenCode) ── 只读:server 列表 + 实时连接状态(经 MCP Gateway 聚合;OpenCode channel 无 getMcpServerStatus,改用 gateway /status 数据源)
    OPENCODE_MCP_SERVER_LIST("opencode.mcp.server_list"),
    OPENCODE_MCP_SERVER_STATUS("opencode.mcp.server_status"),

    // ── MCP Import ── Copilot 配置解析预览(对称 parse_copilot_mcp_config 上行;承载 servers/error)
    MCP_IMPORT_PREVIEW("mcp.import_preview"),

    // ── MCP Market (Smithery Registry) ──
    MCP_MARKET_LIST("mcp.market_list"),
    MCP_MARKET_DETAIL("mcp.market_detail"),
    MCP_MARKET_ERROR("mcp.market_error"),

    // ── Dependency (legacy SDK, kept for backward compat) ──
    DEPENDENCY_STATUS("dependency.status"),
    DEPENDENCY_INSTALL_RESULT("dependency.install_result"),
    DEPENDENCY_UNINSTALL_RESULT("dependency.uninstall_result"),
    DEPENDENCY_UPDATE_AVAILABLE("dependency.update_available"),
    DEPENDENCY_VERSIONS_LOADED("dependency.versions_loaded"),
    DEPENDENCY_INSTALL_PROGRESS("dependency.install_progress"),
    NODE_ENV_STATUS("node.env_status"),

    // ── CLI Environment ──
    CLI_ENVIRONMENT_STATUS("cli_environment.status"),
    CLI_INSTALL_RESULT("cli_environment.install_result"),

    // ── History ──
    HISTORY_EXPORT_DATA("history.export_data"),
    HISTORY_ARCHIVE_RESULT("history.archive_result"),
    HISTORY_CODEX_PAGE_INFO("history.codex.page.info"),
    HISTORY_CODEX_PAGE_ERROR("history.codex.page.error"),

    // ── Input History ──
    INPUT_HISTORY_LOADED("input_history.loaded"),
    INPUT_HISTORY_RECORDED("input_history.recorded"),
    INPUT_HISTORY_DELETED("input_history.deleted"),
    INPUT_HISTORY_CLEARED("input_history.cleared"),

    // ── Node ──
    NODE_PROCESS_LIST("node.process_list"),
    NODE_PROCESS_KILL_RESULT("node.process_kill_result"),
    NODE_PATH("node.path"),
    NODE_CHECK_ENV("node.check_env"),

    // ── Clipboard ──
    CLIPBOARD_READ("clipboard.read"),

    // ── Prompt ──
    PROMPT_PROJECT_INFO("prompt.project_info"),
    PROMPT_GLOBAL_LIST("prompt.global_list"),
    PROMPT_PROJECT_LIST("prompt.project_list"),
    PROMPT_LIST("prompt.list"),
    PROMPT_OPERATION_RESULT("prompt.operation_result"),
    PROMPT_IMPORT_PREVIEW("prompt.import_preview"),
    PROMPT_IMPORT_RESULT("prompt.import_result"),
    PROMPT_ENHANCED("prompt.enhanced"),

    // ── Skill ──
    SKILL_LIST("skill.list"),
    SKILL_IMPORT_RESULT("skill.import_result"),
    SKILL_DELETE_RESULT("skill.delete_result"),
    SKILL_DOCUMENT("skill.document"),
    SKILL_SAVE_RESULT("skill.save_result"),
    SKILL_TOGGLE_RESULT("skill.toggle_result"),

    // ── Skill Market (GitHub tarball) ──
    SKILL_MARKET_LIST("skill.market_list"),
    SKILL_MARKET_INSTALL_RESULT("skill.market_install_result"),
    SKILL_MARKET_DETAIL("skill.market_detail"),
    SKILL_MARKET_ERROR("skill.market_error"),

    // ── File ──
    FILE_LIST_RESULT("file.list_result"),

    // ── Slash ──
    SLASH_DOLLAR_COMMANDS("slash.dollar_commands"),

    // ── Codex ──
    CODEX_SUBSCRIPTION_QUOTA("codex.subscription_quota"),
    ;

    private final String value;

    DownstreamEvent(String value) {
        this.value = value;
    }

    /** 协议线上实际传输的字符串值 */
    public String value() {
        return value;
    }
}
