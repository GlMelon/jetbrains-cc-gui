package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 上行协议动作(前端 → 后端)的唯一权威定义(SSOT)。
 * 前端 TypeScript 常量由此枚举在构建时自动生成。
 *
 * <p>命名规则:枚举常量为 UPPER_SNAKE_CASE,协议值为 snake_case。
 *
 * <p>⚠️ 修改此文件后需运行 {@code webview/scripts/generate-protocol-types.mjs}(或 {@code npm run build})更新前端类型。
 */
public enum UpstreamAction implements ProtocolValue {

    // ── Session ──
    SEND_MESSAGE("send_message"),
    SEND_MESSAGE_WITH_ATTACHMENTS("send_message_with_attachments"),
    INTERRUPT_SESSION("interrupt_session"),
    RESTART_SESSION("restart_session"),
    CREATE_NEW_SESSION("create_new_session"),

    // ── Lifecycle ──
    HEARTBEAT("heartbeat"),
    FRONTEND_READY("frontend_ready"),
    TAB_LOADING_CHANGED("tab_loading_changed"),
    TAB_STATUS_CHANGED("tab_status_changed"),
    REFRESH_SLASH_COMMANDS("refresh_slash_commands"),

    // ── Settings: Mode/Model/Provider ──
    GET_MODE("get_mode"),
    SET_MODE("set_mode"),
    SET_SESSION_MODE("set_session_mode"),
    SET_MODEL("set_model"),
    SET_SESSION_MODEL("set_session_model"),
    SET_PROVIDER("set_provider"),
    SET_SESSION_PROVIDER("set_session_provider"),
    SET_REASONING_EFFORT("set_reasoning_effort"),
    SET_CODEX_FAST_MODE("set_codex_fast_mode"),
    SET_THINKING_ENABLED("set_thinking_enabled"),
    GET_THINKING_ENABLED("get_thinking_enabled"),
    GET_ACTIVE_PROVIDER("get_active_provider"),

    // ── Settings: Config get/set ──
    GET_STREAMING_ENABLED("get_streaming_enabled"),
    SET_STREAMING_ENABLED("set_streaming_enabled"),
    GET_SHOW_THINKING_ENABLED("get_show_thinking_enabled"),
    SET_SHOW_THINKING_ENABLED("set_show_thinking_enabled"),
    GET_SEND_SHORTCUT("get_send_shortcut"),
    SET_SEND_SHORTCUT("set_send_shortcut"),
    GET_AUTO_OPEN_FILE_ENABLED("get_auto_open_file_enabled"),
    SET_AUTO_OPEN_FILE_ENABLED("set_auto_open_file_enabled"),
    GET_PERMISSION_DIALOG_TIMEOUT("get_permission_dialog_timeout"),
    SET_PERMISSION_DIALOG_TIMEOUT("set_permission_dialog_timeout"),
    GET_SESSION_RUNTIME_STATE("get_session_runtime_state"),
    GET_CODEX_SANDBOX_MODE("get_codex_sandbox_mode"),
    SET_CODEX_SANDBOX_MODE("set_codex_sandbox_mode"),
    GET_COMMIT_GENERATION_ENABLED("get_commit_generation_enabled"),
    SET_COMMIT_GENERATION_ENABLED("set_commit_generation_enabled"),
    GET_AI_TITLE_GENERATION_ENABLED("get_ai_title_generation_enabled"),
    SET_AI_TITLE_GENERATION_ENABLED("set_ai_title_generation_enabled"),
    GET_STATUS_BAR_WIDGET_ENABLED("get_status_bar_widget_enabled"),
    SET_STATUS_BAR_WIDGET_ENABLED("set_status_bar_widget_enabled"),
    GET_TASK_COMPLETION_NOTIFICATION_ENABLED("get_task_completion_notification_enabled"),
    SET_TASK_COMPLETION_NOTIFICATION_ENABLED("set_task_completion_notification_enabled"),
    GET_ASK_USER_QUESTION_NOTIFICATION_ENABLED("get_ask_user_question_notification_enabled"),
    SET_ASK_USER_QUESTION_NOTIFICATION_ENABLED("set_ask_user_question_notification_enabled"),
    GET_MCP_GATEWAY_ENABLED("get_mcp_gateway_enabled"),
    SET_MCP_GATEWAY_ENABLED("set_mcp_gateway_enabled"),
    // ── MCP Market (Smithery Registry): API Key 配置读取/写入 ──
    GET_SMITHERY_API_KEY("get_smithery_api_key"),
    SET_SMITHERY_API_KEY("set_smithery_api_key"),

    // ── Settings: Path/Directory ──
    GET_NODE_PATH("get_node_path"),
    SET_NODE_PATH("set_node_path"),
    GET_WORKING_DIRECTORY("get_working_directory"),
    SET_WORKING_DIRECTORY("set_working_directory"),

    // ── Settings: Font ──
    GET_EDITOR_FONT_CONFIG("get_editor_font_config"),
    GET_UI_FONT_CONFIG("get_ui_font_config"),
    SET_UI_FONT_CONFIG("set_ui_font_config"),
    BROWSE_UI_FONT_FILE("browse_ui_font_file"),
    GET_CODE_FONT_CONFIG("get_code_font_config"),
    SET_CODE_FONT_CONFIG("set_code_font_config"),
    BROWSE_CODE_FONT_FILE("browse_code_font_file"),

    // ── Settings: Prompt/Commit ──
    GET_COMMIT_PROMPT("get_commit_prompt"),
    SET_COMMIT_PROMPT("set_commit_prompt"),
    GET_COMMIT_AI_CONFIG("get_commit_ai_config"),
    SET_COMMIT_AI_CONFIG("set_commit_ai_config"),
    GET_PROMPT_ENHANCER_CONFIG("get_prompt_enhancer_config"),
    SET_PROMPT_ENHANCER_CONFIG("set_prompt_enhancer_config"),
    GET_PROJECT_COMMIT_PROMPT("get_project_commit_prompt"),
    SET_PROJECT_COMMIT_PROMPT("set_project_commit_prompt"),

    // ── Settings: Other ──
    GET_USAGE_STATISTICS("get_usage_statistics"),
    GET_CODEX_SUBSCRIPTION_QUOTA("get_codex_subscription_quota"),
    GET_IDE_THEME("get_ide_theme"),
    SET_USER_LANGUAGE("set_user_language"),
    GET_USER_LANGUAGE("get_user_language"),
    CLEAR_USER_LANGUAGE("clear_user_language"),
    SET_APPEARANCE_CONFIG("set_appearance_config"),
    AVATAR_GET_CONFIG("avatar.get_config"),
    AVATAR_SET_CONFIG("avatar.set_config"),
    AVATAR_UPLOAD_CUSTOM("avatar.upload_custom"),

    // ── Model Registry ──
    GET_MODEL_REGISTRY("get_model_registry"),
    SET_MODEL_REGISTRY("set_model_registry"),
    GET_MODEL_REGISTRY_SCHEMA("get_model_registry_schema"),

    // ── Input History ──
    GET_INPUT_HISTORY("get_input_history"),
    RECORD_INPUT_HISTORY("record_input_history"),
    DELETE_INPUT_HISTORY_ITEM("delete_input_history_item"),
    CLEAR_INPUT_HISTORY("clear_input_history"),

    // ── Permission ──
    PERMISSION_DECISION("permission_decision"),
    ASK_USER_QUESTION_RESPONSE("ask_user_question_response"),
    PLAN_APPROVAL_RESPONSE("plan_approval_response"),

    // ── MCP Server (Claude) ──
    GET_MCP_SERVERS("get_mcp_servers"),
    GET_MCP_SERVER_STATUS("get_mcp_server_status"),
    GET_MCP_SERVER_TOOLS("get_mcp_server_tools"),
    ADD_MCP_SERVER("add_mcp_server"),
    UPDATE_MCP_SERVER("update_mcp_server"),
    DELETE_MCP_SERVER("delete_mcp_server"),
    TOGGLE_MCP_SERVER("toggle_mcp_server"),
    VALIDATE_MCP_SERVER("validate_mcp_server"),
    RELOAD_MCP_GATEWAY("reload_mcp_gateway"),

    // ── MCP Server (Codex) ──
    GET_CODEX_MCP_SERVERS("get_codex_mcp_servers"),
    GET_CODEX_MCP_SERVER_STATUS("get_codex_mcp_server_status"),
    GET_CODEX_MCP_SERVER_TOOLS("get_codex_mcp_server_tools"),
    ADD_CODEX_MCP_SERVER("add_codex_mcp_server"),
    UPDATE_CODEX_MCP_SERVER("update_codex_mcp_server"),
    DELETE_CODEX_MCP_SERVER("delete_codex_mcp_server"),
    TOGGLE_CODEX_MCP_SERVER("toggle_codex_mcp_server"),
    VALIDATE_CODEX_MCP_SERVER("validate_codex_mcp_server"),

    // ── MCP Import ── 从外部配置(GitHub Copilot 格式)解析导入 MCP 服务器(业务在后端 McpServerImportService,前端只 paste/预览/确认)
    PARSE_COPILOT_MCP_CONFIG("parse_copilot_mcp_config"),

    // ── MCP Market (Smithery Registry) ── 从市场搜索/获取 MCP 服务器连接配置
    SEARCH_MCP_MARKET("search_mcp_market"),
    GET_MCP_MARKET_DETAIL("get_mcp_market_detail"),

    // ── Agent ──
    GET_AGENTS("get_agents"),
    ADD_AGENT("add_agent"),
    UPDATE_AGENT("update_agent"),
    DELETE_AGENT("delete_agent"),
    GET_SELECTED_AGENT("get_selected_agent"),
    SET_SELECTED_AGENT("set_selected_agent"),
    EXPORT_AGENTS("export_agents"),
    IMPORT_AGENTS_FILE("import_agents_file"),
    SAVE_IMPORTED_AGENTS("save_imported_agents"),

    // ── Skill ──
    GET_ALL_SKILLS("get_all_skills"),
    IMPORT_SKILL("import_skill"),
    DELETE_SKILL("delete_skill"),
    OPEN_SKILL("open_skill"),
    GET_SKILL_DOCUMENT("get_skill_document"),
    SAVE_SKILL_DOCUMENT("save_skill_document"),
    TOGGLE_SKILL("toggle_skill"),

    // ── Skill Market (GitHub tarball) ── 从市场浏览/安装 skill(下载 tarball + 哈希校验)
    LIST_SKILL_MARKET("list_skill_market"),
    INSTALL_SKILL_FROM_MARKET("install_skill_from_market"),
    GET_SKILL_MARKET_DETAIL("get_skill_market_detail"),

    // ── Prompt ──
    GET_PROMPTS("get_prompts"),
    GET_PROJECT_INFO("get_project_info"),
    ADD_PROMPT("add_prompt"),
    UPDATE_PROMPT("update_prompt"),
    DELETE_PROMPT("delete_prompt"),
    EXPORT_PROMPTS("export_prompts"),
    IMPORT_PROMPTS_FILE("import_prompts_file"),
    SAVE_IMPORTED_PROMPTS("save_imported_prompts"),

    // ── Provider (Claude) ──
    GET_PROVIDERS("get_providers"),
    GET_CURRENT_CLAUDE_CONFIG("get_current_claude_config"),
    ADD_PROVIDER("add_provider"),
    UPDATE_PROVIDER("update_provider"),
    DELETE_PROVIDER("delete_provider"),
    SWITCH_PROVIDER("switch_provider"),
    SORT_PROVIDERS("sort_providers"),
    PREVIEW_CC_SWITCH_IMPORT("preview_cc_switch_import"),
    OPEN_FILE_CHOOSER_FOR_CC_SWITCH("open_file_chooser_for_cc_switch"),
    SAVE_IMPORTED_PROVIDERS("save_imported_providers"),
    // 拉取第三方/代理 OpenAI 兼容 models 列表(RPC:业务逻辑下沉后端,前端只做入口)
    FETCH_PROVIDER_MODELS("fetch_provider_models"),

    // ── Provider (Codex) ──
    GET_CODEX_PROVIDERS("get_codex_providers"),
    GET_CURRENT_CODEX_CONFIG("get_current_codex_config"),
    ADD_CODEX_PROVIDER("add_codex_provider"),
    UPDATE_CODEX_PROVIDER("update_codex_provider"),
    DELETE_CODEX_PROVIDER("delete_codex_provider"),
    SWITCH_CODEX_PROVIDER("switch_codex_provider"),
    REVOKE_CODEX_LOCAL_CONFIG_AUTHORIZATION("revoke_codex_local_config_authorization"),
    SORT_CODEX_PROVIDERS("sort_codex_providers"),
    GET_ACTIVE_CODEX_PROVIDER("get_active_codex_provider"),
    PREVIEW_CODEX_CC_SWITCH_IMPORT("preview_codex_cc_switch_import"),
    OPEN_FILE_CHOOSER_FOR_CODEX_CC_SWITCH("open_file_chooser_for_codex_cc_switch"),
    SAVE_IMPORTED_CODEX_PROVIDERS("save_imported_codex_providers"),

    // ── Provider (OpenCode) ── 对称 codex 块(Principle 6 多 provider 对称)
    GET_OPENCODE_PROVIDERS("get_opencode_providers"),
    GET_CURRENT_OPENCODE_CONFIG("get_current_opencode_config"),
    ADD_OPENCODE_PROVIDER("add_opencode_provider"),
    UPDATE_OPENCODE_PROVIDER("update_opencode_provider"),
    DELETE_OPENCODE_PROVIDER("delete_opencode_provider"),
    SWITCH_OPENCODE_PROVIDER("switch_opencode_provider"),
    REVOKE_OPENCODE_LOCAL_CONFIG_AUTHORIZATION("revoke_opencode_local_config_authorization"),
    SORT_OPENCODE_PROVIDERS("sort_opencode_providers"),
    GET_ACTIVE_OPENCODE_PROVIDER("get_active_opencode_provider"),

    // ── Dependency (legacy SDK, kept for backward compat) ──
    GET_DEPENDENCY_STATUS("get_dependency_status"),
    INSTALL_DEPENDENCY("install_dependency"),
    UNINSTALL_DEPENDENCY("uninstall_dependency"),
    UPDATE_DEPENDENCY("update_dependency"),
    CHECK_DEPENDENCY_UPDATES("check_dependency_updates"),
    GET_DEPENDENCY_VERSIONS("get_dependency_versions"),
    CHECK_NODE_ENVIRONMENT("check_node_environment"),

    // ── CLI Environment ──
    CHECK_CLI_ENVIRONMENT("check_cli_environment"),
    UPDATE_CLI_TOOL("update_cli_tool"),

    // ── Node Process ──
    GET_NODE_PROCESSES("get_node_processes"),
    KILL_NODE_PROCESS("kill_node_process"),
    KILL_ALL_ORPHANS("kill_all_orphans"),

    // ── History ──
    LOAD_HISTORY_DATA("load_history_data"),
    LOAD_CODEX_HISTORY_PAGE("load_codex_history_page"),
    LOAD_SESSION("load_session"),
    DELETE_SESSION("delete_session"),
    DELETE_SESSIONS("delete_sessions"),
    ARCHIVE_SESSIONS("archive_sessions"),
    EXPORT_SESSION("export_session"),
    PRINT_SESSION_PDF("print_session_pdf"),
    TOGGLE_FAVORITE("toggle_favorite"),
    UPDATE_TITLE("update_title"),
    DELETE_TITLE("delete_title"),
    DEEP_SEARCH_HISTORY("deep_search_history"),
    LOAD_SUBAGENT_SESSION("load_subagent_session"),
    CONVERT_TO_CLI_SESSION("convert_to_cli_session"),

    // ── File/Diff ──
    OPEN_FILE("open_file"),
    OPEN_CLASS("open_class"),
    OPEN_BROWSER("open_browser"),
    RESOLVE_FILE_PATH("resolve_file_path"),
    REFRESH_FILE("refresh_file"),
    LIST_FILES("list_files"),
    SHOW_DIFF("show_diff"),
    SHOW_MULTI_EDIT_DIFF("show_multi_edit_diff"),
    SHOW_EDITABLE_DIFF("show_editable_diff"),
    SHOW_EDIT_PREVIEW_DIFF("show_edit_preview_diff"),
    SHOW_EDIT_FULL_DIFF("show_edit_full_diff"),
    SHOW_INTERACTIVE_DIFF("show_interactive_diff"),
    REWIND_FILES("rewind_files"),
    UNDO_FILE_CHANGES("undo_file_changes"),
    UNDO_ALL_FILE_CHANGES("undo_all_file_changes"),

    // ── File Export ──
    SAVE_JSON("save_json"),
    SAVE_MARKDOWN("save_markdown"),
    SAVE_EXPORTED_FILE("save_exported_file"),

    // ── Clipboard ──
    READ_CLIPBOARD("read_clipboard"),
    WRITE_CLIPBOARD("write_clipboard"),

    // ── Other ──
    GET_CONTEXT_USAGE("get_context_usage"),
    CREATE_NEW_TAB("create_new_tab"),
    GET_LINKIFY_CAPABILITIES("get_linkify_capabilities"),
    ENHANCE_PROMPT("enhance_prompt"),
    ;

    private final String value;

    UpstreamAction(String value) {
        this.value = value;
    }

    /** 协议线上实际传输的字符串值 */
    public String value() {
        return value;
    }

    public static Optional<UpstreamAction> fromValue(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
