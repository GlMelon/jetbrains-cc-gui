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

    // ── Usage/Settings ──
    USAGE_UPDATE("usage.update"),
    USAGE_STATISTICS("usage.statistics"),
    SETTING_STREAMING_ENABLED("setting.streaming_enabled"),
    SETTING_SEND_SHORTCUT("setting.send_shortcut"),
    SETTING_AUTO_OPEN_FILE("setting.auto_open_file"),
    SETTING_THINKING_ENABLED("setting.thinking_enabled"),
    SETTING_PERMISSION_DIALOG_TIMEOUT("setting.permission_dialog_timeout"),

    // ── Mode/Model ──
    MODE_CHANGED("mode.changed"),
    MODE_RECEIVED("mode.received"),
    MODEL_CHANGED("model.changed"),
    MODEL_CONFIRMED("model.confirmed"),
    MODEL_SELECTION("model.selection"),

    // ── Provider ──
    PROVIDER_LIST("provider.list"),
    PROVIDER_ACTIVE("provider.active"),
    PROVIDER_CODEX_LIST("provider.codex_list"),
    PROVIDER_ACTIVE_CODEX("provider.active_codex"),
    PROVIDER_CLAUDE_CONFIG("provider.claude_config"),
    PROVIDER_CODEX_CONFIG("provider.codex_config"),
    PROVIDER_CLI_LOGIN_ACCOUNT("provider.cli_login_account"),
    PROVIDER_IMPORT_PREVIEW("provider.import_preview"),

    // ── Session ──
    SESSION_INVOCATION_MODE("session.invocation_mode"),
    SESSION_RUNTIME_STATE("session.runtime_state"),
    SESSION_TITLE("session.title"),

    // ── RPC ──
    FILE_PATH_RESOLVE("file_path.resolve"),
    FILE_PATH_RESOLVED("file_path.resolved"),

    // ── Streaming ──
    STREAM_START("stream.start"),
    STREAM_CONTENT_DELTA("stream.content_delta"),
    STREAM_THINKING_DELTA("stream.thinking_delta"),
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
    CONFIG_INVOCATION_MODE("config.invocation_mode"),
    CONFIG_CLAUDE_CLI_PATH("config.claude_cli_path"),
    CONFIG_COMMIT_PROMPT("config.commit_prompt"),
    CONFIG_PROMPT_ENHANCER("config.prompt_enhancer"),
    CONFIG_COMMIT_AI("config.commit_ai"),
    CONFIG_PROJECT_COMMIT_PROMPT("config.project_commit_prompt"),
    CONFIG_COMMIT_GENERATION("config.commit_generation"),
    CONFIG_AI_TITLE_GENERATION("config.ai_title_generation"),
    CONFIG_STATUS_BAR_WIDGET("config.status_bar_widget"),
    CONFIG_TASK_COMPLETION_NOTIFICATION("config.task_completion_notification"),

    // ── Runtime Policy ──
    RUNTIME_POLICY("runtime_policy"),
    RUNTIME_POLICY_ERROR("runtime_policy_error"),
    RUNTIME_POLICY_UPDATED("runtime_policy_updated"),
    RUNTIME_POLICY_SCHEMA("runtime_policy_schema"),

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

    // ── MCP Server (Codex) ──
    CODEX_MCP_SERVER_LIST("codex.mcp.server_list"),
    CODEX_MCP_SERVER_STATUS("codex.mcp.server_status"),
    CODEX_MCP_SERVER_TOOLS("codex.mcp.server_tools"),
    CODEX_MCP_SERVER_ADDED("codex.mcp.server_added"),
    CODEX_MCP_SERVER_UPDATED("codex.mcp.server_updated"),
    CODEX_MCP_SERVER_DELETED("codex.mcp.server_deleted"),
    CODEX_MCP_SERVER_TOGGLED("codex.mcp.server_toggled"),
    CODEX_MCP_SERVER_VALIDATED("codex.mcp.server_validated"),

    // ── Dependency ──
    DEPENDENCY_STATUS("dependency.status"),
    DEPENDENCY_INSTALL_RESULT("dependency.install_result"),
    DEPENDENCY_UNINSTALL_RESULT("dependency.uninstall_result"),
    DEPENDENCY_UPDATE_AVAILABLE("dependency.update_available"),
    DEPENDENCY_VERSIONS_LOADED("dependency.versions_loaded"),
    DEPENDENCY_INSTALL_PROGRESS("dependency.install_progress"),
    NODE_ENV_STATUS("node.env_status"),

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
    SKILL_TOGGLE_RESULT("skill.toggle_result"),

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
