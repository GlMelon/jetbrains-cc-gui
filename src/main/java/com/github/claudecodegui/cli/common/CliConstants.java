package com.github.claudecodegui.cli.common;

import java.util.List;
import java.util.Set;

/**
 * CLI 包全局常量：集中管理 provider 标识、消息类型、CLI 参数、环境变量名、sandbox 模式等魔法字符串。
 */
public final class CliConstants {

    private CliConstants() {
    }

    // ── Provider 标识 ──────────────────────────────────────────────────────────
    // 已迁移至 CommonConstants（统一权威定义，消除重复）。请引用 CommonConstants.PROVIDER_*。

    /** 进程等待超时(毫秒),防止中断未真正结束子进程时 waitFor 永久阻塞导致 Future 无法完成。 */
    public static final long PROCESS_WAIT_TIMEOUT_MS = 30_000L;

    // ── I18N 消息键 ────────────────────────────────────────────────────────────

    public static final String I18N_REQUEST_INTERRUPTED = "__I18N__:chat.requestInterrupted";
    public static final String I18N_UNSUPPORTED_IMAGE = "__I18N__:aiBridge.unsupportedImageVision";

    // ── 回调消息类型 ───────────────────────────────────────────────────────────

    public static final String MSG_SESSION_ID = "session_id";
    public static final String MSG_STREAM_START = "stream_start";
    public static final String MSG_STREAM_END = "stream_end";
    public static final String MSG_MESSAGE_START = "message_start";
    public static final String MSG_MESSAGE_END = "message_end";
    public static final String MSG_CONTENT_DELTA = "content_delta";
    // MSG_THINKING 已合并至 CommonConstants.MSG_TYPE_THINKING（通用消息类型统一归 common）
    public static final String MSG_THINKING_DELTA = "thinking_delta";
    public static final String MSG_BLOCK_RESET = "block_reset";
    public static final String MSG_USAGE = "usage";
    public static final String MSG_RESULT = "result";
    // MSG_ASSISTANT/MSG_USER 已合并至 CommonConstants.MSG_TYPE_ASSISTANT/MSG_TYPE_USER
    /** stream-json 顶层事件类型：内部承载 Anthropic SSE 事件（content_block_* 等）。 */
    public static final String MSG_STREAM_EVENT = "stream_event";
    /** 非流式完整回复内容（result 事件兜底回填 assistant 内容）。 */
    public static final String MSG_CONTENT = "content";
    /** 可用斜杠命令列表事件。 */
    public static final String MSG_SLASH_COMMANDS = "slash_commands";
    /** Node.js 进程日志转发事件。 */
    public static final String MSG_NODE_LOG = "node_log";

    // ── Codex 专有协议事件类型（Codex message-service wire 协议） ──────────────

    public static final String CODEX_MSG_EVENT_MSG = "event_msg";
    public static final String CODEX_MSG_STATUS = "status";
    public static final String CODEX_MSG_TOKEN_COUNT = "token_count";

    // ── Codex 环境变量配置 category / 字段名 ──────────────────────────────────

    public static final String CODEX_CATEGORY_MESSAGE = "message";
    public static final String CODEX_FIELD_MESSAGE_ENV_VARS = "messageEnvVars";
    public static final String CODEX_FIELD_MCP_ENV_VARS = "mcpEnvVars";

    // ── Codex CLI 流式事件类型（--json stream type 字段） ──────────────────────
    // Codex CLI 流式输出的事件类型，由 CodexCliSession 解析。集中管理以便协议升级时单点修改。

    public static final String CODEX_EVENT_THREAD_STARTED = "thread.started";
    public static final String CODEX_EVENT_TURN_STARTED = "turn.started";
    public static final String CODEX_EVENT_ITEM_STARTED = "item.started";
    public static final String CODEX_EVENT_ITEM_UPDATED = "item.updated";
    public static final String CODEX_EVENT_ITEM_COMPLETED = "item.completed";
    public static final String CODEX_EVENT_RESPONSE_ITEM = "response_item";
    public static final String CODEX_EVENT_TURN_COMPLETED = "turn.completed";
    public static final String CODEX_EVENT_TURN_FAILED = "turn.failed";
    public static final String CODEX_EVENT_ERROR = "error";

    // ── Codex CLI item.type 值 ────────────────────────────────────────────────

    public static final String CODEX_ITEM_REASONING = "reasoning";
    public static final String CODEX_ITEM_AGENT_MESSAGE = "agent_message";
    public static final String CODEX_ITEM_COMMAND_EXECUTION = "command_execution";
    public static final String CODEX_ITEM_MCP_TOOL_CALL = "mcp_tool_call";

    // ── Codex CLI response payload.type 值 ────────────────────────────────────

    public static final String CODEX_PAYLOAD_FUNCTION_CALL = "function_call";
    public static final String CODEX_PAYLOAD_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String CODEX_PAYLOAD_CUSTOM_TOOL_CALL = "custom_tool_call";

    // ── Codex CLI item.status 值（失败状态） ──────────────────────────────────

    public static final String CODEX_STATUS_FAILED = "failed";
    public static final String CODEX_STATUS_ERROR = "error";

    // ── Codex 历史回放消息 type 值（HistoryMessageInjector 解析） ──────────────

    public static final String CODEX_MSG_SESSION_META = "session_meta";
    public static final String CODEX_MSG_PROVIDER_ERROR = "provider_error";

    // ── Codex response payload 扩展 type 值 ───────────────────────────────────
    // 注意 CODEX_PAYLOAD_MESSAGE 与 CODEX_CATEGORY_MESSAGE 同值 "message"，但语义不同：
    // 此处为 response payload 的 type；CODEX_CATEGORY_MESSAGE 为 env config 的 category。

    public static final String CODEX_PAYLOAD_MESSAGE = "message";
    public static final String CODEX_PAYLOAD_USER_MESSAGE = "user_message";

    // ── Anthropic 流式事件类型（stream_event.event.type / delta.type 解析） ────
    // 这些是 Anthropic Messages API 的 wire 事件名，由 claude CLI stream-json 透传，
    // 仅在 ClaudeCliStreamParser 中解析。集中管理以便协议升级时单点修改。

    public static final String STREAM_CONTENT_BLOCK_START = "content_block_start";
    public static final String STREAM_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String STREAM_CONTENT_BLOCK_STOP = "content_block_stop";
    public static final String STREAM_MESSAGE_DELTA = "message_delta";
    public static final String STREAM_MESSAGE_STOP = "message_stop";
    /** content_block_delta 的 delta.type 文本增量（thinking_delta 复用 MSG_THINKING_DELTA）。 */
    public static final String DELTA_TEXT = "text_delta";
    /** content_block_delta 的 delta.type 工具输入增量（部分输入，解析器跳过）。 */
    public static final String DELTA_INPUT_JSON = "input_json_delta";
    /** system 事件 subtype：init 提取 session_id，status（如 requesting）跳过。 */
    public static final String SUBTYPE_INIT = "init";

    // ── Claude CLI 参数 ────────────────────────────────────────────────────────

    public static final String ARG_P = "-p";
    public static final String ARG_OUTPUT_FORMAT = "--output-format";
    public static final String ARG_STREAM_JSON = "stream-json";
    public static final String ARG_VERBOSE = "--verbose";
    public static final String ARG_INCLUDE_PARTIAL = "--include-partial-messages";
    public static final String ARG_PERMISSION_MODE = "--permission-mode";
    public static final String ARG_DANGEROUS_SKIP = "--dangerously-skip-permissions";
    public static final String ARG_MODEL = "--model";
    public static final String ARG_EFFORT = "--effort";
    public static final String ARG_MCP_CONFIG = "--mcp-config";
    public static final String ARG_ADD_DIR = "--add-dir";
    public static final String ARG_RESUME = "--resume";
    public static final String ARG_NO_COLOR = "NO_COLOR";

    // ── Codex CLI 参数 ─────────────────────────────────────────────────────────

    public static final String CODEX_ARG_EXEC = "exec";
    public static final String CODEX_ARG_RESUME = "resume";
    public static final String CODEX_ARG_JSON = "--json";
    public static final String CODEX_ARG_COLOR = "--color";
    public static final String CODEX_ARG_NEVER = "never";
    public static final String CODEX_ARG_SANDBOX = "--sandbox";
    public static final String CODEX_ARG_C = "-C";
    public static final String CODEX_ARG_M = "-m";
    public static final String CODEX_ARG_IMAGE = "--image";
    public static final String CODEX_ARG_SEPARATOR = "--";
    public static final String CODEX_ARG_STDIN = "-";
    public static final String CODEX_ARG_ASK_APPROVAL = "--ask-for-approval";
    public static final String CODEX_ARG_LAST = "--last";
    public static final String CODEX_ARG_C_CONFIG = "-c";
    public static final String CODEX_ARG_I_CONFIG = "-i";

    // ── OpenCode CLI 参数 ────────────────────────────────────────────────────────

    public static final String OPENCODE_ARG_API = "api";
    public static final String OPENCODE_ARG_DATA = "-d";
    public static final String OPENCODE_ARG_HEADER = "-H";
    public static final String OPENCODE_ARG_SERVE = "serve";
    public static final String OPENCODE_ARG_SERVICE = "service";
    public static final String OPENCODE_ARG_START = "start";
    public static final String OPENCODE_ARG_STOP = "stop";
    public static final String OPENCODE_ARG_STATUS = "status";
    public static final String OPENCODE_ARG_PORT = "--port";
    public static final String OPENCODE_ARG_HOSTNAME = "--hostname";

    // ── OpenCode API 端点 ───────────────────────────────────────────────────────

    public static final String OPENCODE_API_SESSION_CREATE = "POST /api/session";
    public static final String OPENCODE_API_SESSION_PROMPT = "POST /api/session/:id/prompt";
    public static final String OPENCODE_API_SESSION_ABORT = "POST /api/session/:id/abort";
    public static final String OPENCODE_API_SESSION_EVENT = "GET /api/session/:id/event";
    public static final String OPENCODE_API_HEALTH = "GET /api/health";

    // ── Sandbox 模式值 ─────────────────────────────────────────────────────────

    public static final String SANDBOX_READ_ONLY = "read-only";
    public static final String SANDBOX_WORKSPACE_WRITE = "workspace-write";
    public static final String SANDBOX_DANGER_FULL_ACCESS = "danger-full-access";

    public static final Set<String> VALID_SANDBOX_MODES = Set.of(
            SANDBOX_READ_ONLY, SANDBOX_WORKSPACE_WRITE, SANDBOX_DANGER_FULL_ACCESS
    );

    // ── 权限模式值 ─────────────────────────────────────────────────────────────

    public static final String PERM_BYPASS = "bypassPermissions";
    public static final String PERM_DEFAULT = "default";
    public static final String PERM_ACCEPT_EDITS = "acceptEdits";
    public static final String PERM_PLAN = "plan";
    public static final String PERM_AUTO_EDIT = "autoEdit";

    public static final Set<String> VALID_PERMISSION_MODES = Set.of(
            PERM_DEFAULT, PERM_ACCEPT_EDITS, PERM_PLAN
    );

    // ── 环境变量名 (Anthropic / Claude) ────────────────────────────────────────
    // 已迁出至 CommonConstants（统一权威定义，消除重复）。请引用 CommonConstants.ENV_ANTHROPIC_*。

    // ── 环境变量名 (Codex / OpenAI) ────────────────────────────────────────────

    public static final String ENV_CODEX_HOME = "CODEX_HOME";
    public static final String ENV_CODEX_MODEL = "CODEX_MODEL";
    public static final String ENV_CODEX_SANDBOX = "CODEX_SANDBOX";
    public static final String ENV_CODEX_SANDBOX_MODE = "CODEX_SANDBOX_MODE";
    public static final String ENV_CODEX_SANDBOX_NETWORK_DISABLED = "CODEX_SANDBOX_NETWORK_DISABLED";
    public static final String ENV_CODEX_USE_STDIN = "CODEX_USE_STDIN";
    public static final String ENV_CODEX_APPROVAL_POLICY = "CODEX_APPROVAL_POLICY";
    public static final String ENV_CODEX_CI = "CODEX_CI";
    public static final String ENV_CLAUDE_USE_STDIN = "CLAUDE_USE_STDIN";
    public static final String ENV_OPENAI_BASE_URL = "OPENAI_BASE_URL";
    public static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";
    public static final String ENV_OPENAI_ORG_ID = "OPENAI_ORG_ID";
    public static final String ENV_OPENAI_PROJECT_ID = "OPENAI_PROJECT_ID";
    public static final String ENV_EACASE_API_KEY = "EACASE_API_KEY";

    /** Codex 认证相关环境变量键列表。 */
    public static final List<String> CODEX_AUTH_ENV_KEYS = List.of(
            ENV_OPENAI_API_KEY, ENV_OPENAI_BASE_URL,
            ENV_OPENAI_ORG_ID, ENV_OPENAI_PROJECT_ID, ENV_EACASE_API_KEY
    );

    // ── 环境变量名 (Claude 权限 / 会话) ────────────────────────────────────────

    public static final String ENV_CLAUDE_SESSION_ID = "CLAUDE_SESSION_ID";
    public static final String ENV_CLAUDE_PERMISSION_DIR = "CLAUDE_PERMISSION_DIR";
    public static final String ENV_CLAUDE_PERMISSION_SAFETY_NET_MS = "CLAUDE_PERMISSION_SAFETY_NET_MS";
    public static final String ENV_IDEA_PROJECT_PATH = "IDEA_PROJECT_PATH";
    public static final String ENV_PROJECT_PATH = "PROJECT_PATH";

    // ── MCP 配置 ───────────────────────────────────────────────────────────────

    public static final String MCP_SERVERS_KEY = "mcpServers";

    // ── 图片相关 ───────────────────────────────────────────────────────────────

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"
    );

    // ── Claude 内置工具名 ──────────────────────────────────────────────────────

    /** Claude 内置工具：读取文件（用于识别图片读取场景）。 */
    public static final String TOOL_NAME_READ = "Read";

    // ── Codex CLI 工具名（CodexMessageConverter 识别） ────────────────────────

    public static final String CODEX_TOOL_SHELL_COMMAND = "shell_command";
    public static final String CODEX_TOOL_UPDATE_PLAN = "update_plan";
    public static final String CODEX_TOOL_WRITE_STDIN = "write_stdin";
    public static final String CODEX_TOOL_EXEC_COMMAND = "exec_command";
    public static final String CODEX_TOOL_WRITE = "write";
    public static final String CODEX_TOOL_TODO_WRITE = "todowrite";
    public static final String CODEX_TOOL_APPLY_PATCH = "apply_patch";

    // ── Prompt 模板片段 ────────────────────────────────────────────────────────

    public static final String PROMPT_OPENED_FILES = "\n\n## Opened Files Context\n\n";
    public static final String PROMPT_REFERENCED = "\n\n## Referenced Files\n\n";
    public static final String PROMPT_AGENT_ROLE = "\n\n## Agent Role and Instructions\n\n";
    public static final String PROMPT_READ_IMAGE = "Use the Read tool to inspect this image file";

    // ── 正常流式 JSON 事件前缀（用于 CliErrorFormatter 过滤） ───────────────────

    public static final List<String> NORMAL_STREAM_EVENT_PREFIXES = List.of(
            "{\"type\":\"system\"",
            "{\"type\":\"stream_event\"",
            "{\"type\":\"assistant\"",
            "{\"type\":\"user\"",
            "{\"type\":\"result\""
    );

    // ── 错误摘要关键词规则 ─────────────────────────────────────────────────────

    public static final List<String> TIMEOUT_KEYWORDS = List.of("timeout", "timed out");
    public static final List<String> AUTH_KEYWORDS = List.of("unauthorized", "authentication", "auth failed");
    public static final List<String> RATE_LIMIT_KEYWORDS = List.of("rate limit", "quota");
    public static final List<String> NETWORK_KEYWORDS = List.of("network", "connection", "dns");

    // ── Windows 系统环境变量名 ─────────────────────────────────────────────────

    public static final List<String> WINDOWS_SYSTEM_ENV_KEYS = List.of(
            "SystemRoot", "ComSpec", "PATHEXT", "WINDIR", "NUMBER_OF_PROCESSORS"
    );

    // ── 终端/区域设置环境变量名 ────────────────────────────────────────────────

    public static final List<String> TERMINAL_HINT_ENV_KEYS = List.of(
            "TERM", "TERM_PROGRAM", "COLORTERM", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP"
    );

    // ── 代理环境变量名 ─────────────────────────────────────────────────────────

    public static final List<String> PROXY_ENV_KEYS = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"
    );

    // ── Windows 脚本后缀 ───────────────────────────────────────────────────────

    public static final List<String> WINDOWS_SCRIPT_SUFFIXES = List.of(".cmd", ".bat");

    // ── PowerShell 诊断行前缀 ──────────────────────────────────────────────────

    public static final List<String> POWERSHELL_DIAGNOSTIC_PREFIXES = List.of(
            "At line:", "CategoryInfo :", "FullyQualifiedErrorId :"
    );

    // ── CliAttachmentHandler 日志前缀 ──────────────────────────────────────────

    public static final String LOG_PREFIX_IMAGE_DIAG = "[ClaudeImageDiag][CliAttachmentHandler] ";

    // ── 模型家族标识 ───────────────────────────────────────────────────────────

    public static final String MODEL_PREFIX = "claude-";
    public static final String MODEL_PREFIX_ALT = "claude_";
}
