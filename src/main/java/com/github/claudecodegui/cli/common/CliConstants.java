package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.common.CommonConstants;

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
    /** 单轮 CLI 请求绝对超时。 */
    public static final long CLI_REQUEST_TIMEOUT_MS = 15 * 60 * 1000L;
    /** 进程退出后等待 stdout drain 完成的最大时长。 */
    public static final long OUTPUT_DRAIN_TIMEOUT_MS = 5_000L;

    // ── 长驻会话(CLI persistent process)常量 ──────────────────────────────────

    /** 优雅关闭:stdin EOF 后等待 CLI 自然退出的上限,超时走 terminateProcess 兜底。 */
    public static final long CLI_GRACEFUL_CLOSE_TIMEOUT_MS = 5_000L;
    /** 空闲回收阈值:超过此时长无活动的长驻进程静默优雅关闭。 */
    public static final long CLI_PERSISTENT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** 空闲回收扫描间隔。 */
    public static final long CLI_PERSISTENT_SWEEP_INTERVAL_MS = 5 * 60 * 1000L;
    /** 单项目长驻 CLI 进程上限,超限新 tab 自动降级 one-shot(不报错不打扰)。 */
    public static final int CLI_PERSISTENT_MAX_PROCESSES = 8;
    /** abort 兜底:interrupt control_request 写入后此时长无 result 回应则杀进程树。 */
    public static final long CLI_INTERRUPT_FALLBACK_MS = 3_000L;
    /** 长驻 spawn 后的速死观察窗(命令行/认证错误立即退出在此窗内暴露;活进程等满窗口,仅首条消息付)。 */
    public static final long CLI_PERSISTENT_READY_WINDOW_MS = 300L;
    /** 项目 dispose 清理时单个进程的优雅关闭等待上限(短等待+强杀兜底,避免阻塞项目关闭)。 */
    public static final long CLI_DISPOSE_CLOSE_TIMEOUT_MS = 1_000L;
    /** 坏槽位重建:连续 spawn 失败次数上限,达到后该键进入冷却窗口(不无限重启)。 */
    public static final int CLI_PERSISTENT_REBUILD_MAX_FAILURES = 3;
    /** 坏槽位重建冷却窗口:连续失败达上限后,该键在此窗口内不再尝试 spawn,消息直接走 one-shot。 */
    public static final long CLI_PERSISTENT_REBUILD_COOLDOWN_MS = 60_000L;
    /** 轮外协议事件 WARN 限流:每进程最多打此条数,之后降 debug(防刷屏)。 */
    public static final int CLI_PERSISTENT_ORPHAN_WARN_LIMIT = 5;

    // ── 长驻路径决策日志 reason 值(path/fallbackReason 字段) ──

    /** 门禁关闭(总开关/user 开关/provider 子开关)。 */
    public static final String PATH_REASON_FLAG_DISABLED = "flag_disabled";
    /** CLI 版本按最新 compatibility manifest 判定不兼容(版本门禁)。 */
    public static final String PATH_REASON_VERSION_INCOMPATIBLE = "version_incompatible";
    /** acquire 未命中(指纹漂移/崩溃槽/超限/冷却),细节见 registry 日志。 */
    public static final String PATH_REASON_REGISTRY_MISS = "registry_miss";
    /** startTurn 即时失败(进程死/stdin 写入失败):消息未递交,静默降级 one-shot 安全。 */
    public static final String PATH_REASON_START_FAILED = "start_failed";

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
    /**
     * 会话标题事件:kimi ACP 等通道从 {@code session_info_update.title} 捕获的原生标题,
     * 经 {@link com.github.claudecodegui.session.CliSessionTitleService} 接入。
     * content 形如 {@code {"sessionId":"...","title":"..."}}。
     */
    public static final String MSG_SESSION_TITLE = "session_title";
    /** Node.js 进程日志转发事件。 */
    public static final String MSG_NODE_LOG = "node_log";
    /**
     * 响应阶段状态事件:复用 onMessage 通道下发 {@link com.github.claudecodegui.session.AssistantResponsePhase}
     * 的 value(如 {@code mcp_syncing}/{@code connecting}),或特殊信号 {@link #PHASE_API_RETRY}。
     * CLI send 路径在 gateway 构建/spawn 等真实边界上报,使前端状态条按 CLI 启动时间线细分阶段。
     */
    public static final String MSG_RESPONSE_PHASE = "response_phase";
    /**
     * api_retry 信号:API 端点 5xx/529 过载,CLI 静默指数退避重试中。
     * content 形如 {@code api_retry:attempt:maxRetries}(attempt 递增即每次重试都下发,使顶部状态卡
     * 持续刷新"正在重连 N/M")。handler 收到后构造 {@code API_RETRY} phase + 带计数 description,
     * 前端据此切琥珀警示色,使静默挂起全程可感知。与 {@link #SUBTYPE_API_RETRY} 同源。
     */
    public static final String PHASE_API_RETRY = "api_retry";

    // ── Codex 专有协议事件类型（Codex message-service wire 协议） ──────────────

    public static final String CODEX_MSG_EVENT_MSG = "event_msg";
    public static final String CODEX_MSG_STATUS = "status";
    public static final String CODEX_MSG_TOKEN_COUNT = "token_count";

    // ── Codex 环境变量配置 category / 字段名 ──────────────────────────────────

    public static final String CODEX_CATEGORY_MESSAGE = "message";
    public static final String CODEX_CATEGORY_MCP = "mcp";
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
    public static final String CODEX_ITEM_AGENT_REASONING = "agent_reasoning";
    public static final String CODEX_ITEM_AGENT_MESSAGE = "agent_message";
    public static final String CODEX_ITEM_COMMAND_EXECUTION = "command_execution";
    public static final String CODEX_ITEM_MCP_TOOL_CALL = "mcp_tool_call";
    public static final String CODEX_ITEM_FILE_CHANGE = "file_change";
    public static final String CODEX_ITEM_WEB_SEARCH = "web_search";
    public static final String CODEX_ITEM_TODO_LIST = "todo_list";
    public static final String CODEX_ITEM_FUNCTION_CALL = "function_call";
    public static final String CODEX_ITEM_TOOL_CALL = "tool_call";
    public static final String CODEX_ITEM_CUSTOM_TOOL_CALL = "custom_tool_call";
    public static final String CODEX_ITEM_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String CODEX_ITEM_ERROR = "error";

    // ── Codex CLI response payload.type 值 ────────────────────────────────────

    public static final String CODEX_PAYLOAD_FUNCTION_CALL = "function_call";
    public static final String CODEX_PAYLOAD_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String CODEX_PAYLOAD_CUSTOM_TOOL_CALL = "custom_tool_call";
    public static final String CODEX_PAYLOAD_REASONING = CODEX_ITEM_REASONING;
    public static final String CODEX_PAYLOAD_AGENT_REASONING = CODEX_ITEM_AGENT_REASONING;

    // ── Codex CLI 字段名 ────────────────────────────────────────────────────

    public static final String CODEX_FIELD_ENCRYPTED_CONTENT = "encrypted_content";
    public static final String CODEX_FIELD_FILE_PATH = "file_path";
    public static final String CODEX_FIELD_FILE = "file";
    public static final String CODEX_FIELD_FILENAME = "filename";
    public static final String CODEX_FIELD_ACTION = "action";
    public static final String CODEX_FIELD_CHANGE_TYPE = "change_type";
    public static final String CODEX_FIELD_ARGUMENTS = "arguments";
    public static final String CODEX_FIELD_TODOS = "todos";
    public static final String CODEX_FIELD_TASKS = "tasks";
    public static final String CODEX_FIELD_CALL_ID = "call_id";

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
    /** content_block_delta 的 delta.type 工具输入增量。 */
    public static final String DELTA_INPUT_JSON = "input_json_delta";
    /** input_json_delta 的增量 JSON 片段字段。 */
    public static final String JSON_KEY_PARTIAL_JSON = "partial_json";
    /** system 事件 subtype：init 提取 session_id，status（如 requesting）跳过。 */
    public static final String SUBTYPE_INIT = "init";
    /** system 事件 subtype：api_retry = API 端点 5xx/529 过载，CLI 静默指数退避重试中。 */
    public static final String SUBTYPE_API_RETRY = "api_retry";

    // ── Claude CLI 参数 ────────────────────────────────────────────────────────

    public static final String ARG_P = "-p";
    public static final String ARG_INPUT_FORMAT = "--input-format";
    public static final String ARG_OUTPUT_FORMAT = "--output-format";
    public static final String ARG_STREAM_JSON = "stream-json";
    public static final String ARG_VERBOSE = "--verbose";
    public static final String ARG_INCLUDE_PARTIAL = "--include-partial-messages";
    public static final String ARG_PERMISSION_MODE = "--permission-mode";
    public static final String ARG_DANGEROUS_SKIP = "--dangerously-skip-permissions";
    /** Injects a settings file (additive merge, high precedence) — used to attach the PreToolUse hook. */
    public static final String ARG_SETTINGS = "--settings";
    public static final String ARG_MODEL = "--model";
    public static final String ARG_EFFORT = "--effort";
    public static final String ARG_MCP_CONFIG = "--mcp-config";
    public static final String ARG_ADD_DIR = "--add-dir";
    public static final String ARG_RESUME = "--resume";
    public static final String ARG_REWIND_FILES = "--rewind-files";
    public static final String ARG_NO_COLOR = "NO_COLOR";

    /** Enables file checkpoints for Claude's non-interactive (-p) CLI mode. */
    public static final String ENV_CLAUDE_ENABLE_SDK_FILE_CHECKPOINTING =
            "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING";
    public static final String ENV_TRUE = "true";
    public static final String ENV_ENABLED = "1";

    // ── Codex CLI 参数 ─────────────────────────────────────────────────────────

    public static final String CODEX_ARG_EXEC = "exec";
    public static final String CODEX_ARG_RESUME = "resume";
    public static final String CODEX_ARG_JSON = "--json";
    public static final String CODEX_ARG_COLOR = "--color";
    public static final String CODEX_ARG_NEVER = "never";
    /** Codex CLI v0.149.0 removed 'untrusted'; its ask-before-run semantics merged into 'on-request'. */
    public static final String CODEX_ARG_APPROVAL_ON_REQUEST = "on-request";
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
    public static final String CODEX_CONFIG_MODEL_REASONING_EFFORT = "model_reasoning_effort";
    public static final String CODEX_CONFIG_MODEL_REASONING_SUMMARY = "model_reasoning_summary";
    public static final String CODEX_CONFIG_SERVICE_TIER = "service_tier";
    public static final String CODEX_REASONING_SUMMARY_AUTO = "auto";

    // ── OpenCode CLI 参数（实测 opencode v1.17.11 `opencode run --help`） ──────
    // 真实命令：opencode run [message..] --format json（输出逐行 NDJSON 事件流）。
    // opencode 无 `api`/`service` 子命令（早期臆造常量已于 §15.4 重写后删除）。

    /** opencode 子命令：非交互运行并发送消息。 */
    public static final String OPENCODE_ARG_RUN = "run";
    /** --format：输出格式。 */
    public static final String OPENCODE_ARG_FORMAT = "--format";
    /** --format json：原始 JSON 事件流（逐行 NDJSON）。 */
    public static final String OPENCODE_FORMAT_JSON = "json";
    /** -m/--model：provider/model 格式（聚合器，如 anthropic/claude-3-5-sonnet）。 */
    public static final String OPENCODE_ARG_MODEL = "-m";
    /** -s/--session：续接指定 session id。 */
    public static final String OPENCODE_ARG_SESSION = "-s";
    /** -c/--continue：续接上一会话。 */
    public static final String OPENCODE_ARG_CONTINUE = "-c";
    /** -f/--file：附件（数组，可多次）。 */
    public static final String OPENCODE_ARG_FILE = "-f";
    /** --variant：provider 特定推理级别（minimal/high/max）。 */
    public static final String OPENCODE_ARG_VARIANT = "--variant";
    /** --thinking：显示思考块。 */
    public static final String OPENCODE_ARG_THINKING = "--thinking";
    /** --dir：运行目录。 */
    public static final String OPENCODE_ARG_DIR = "--dir";
    /** --agent：使用的 agent。 */
    public static final String OPENCODE_ARG_AGENT = "--agent";
    /** --attach：连接运行中的 opencode serve（避免 MCP 冷启动）。 */
    public static final String OPENCODE_ARG_ATTACH = "--attach";
    /** --auto:自动批准未被 opencode permission 配置显式拒绝(deny)的权限请求(对应 bypass/yolo)。
     *  opencode 官方 bypass 等价物,见 https://opencode.ai/docs/permissions/ 。
     *  注:opencode 无 --dangerously-skip-permissions(那是 Claude/Codex 的 flag,opencode 不识别),
     *  早期误用此 flag 已修正为 --auto。 */
    public static final String OPENCODE_ARG_AUTO = "--auto";

    // ── OpenCode 环境变量 ───────────────────────────────────────────────────────

    /** opencode 权限配置（内联 JSON），映射本项目 permissionMode。 */
    public static final String ENV_OPENCODE_PERMISSION = "OPENCODE_PERMISSION";

    // ── Grok CLI 参数（headless 模式,对齐 docs.x.ai/build/cli/headless-scripting） ──
    // 真实命令：grok -p <prompt> --output-format streaming-json --always-approve
    //          [-m <profile>] [--reasoning-effort low|medium|high] (-s <new-uuid> | -r <existing-uuid>)
    // 会话存 ~/.grok/sessions/<encodeURIComponent(cwd)>/<sessionId>/。

    /** -p/--single：发送单条 prompt（headless）。 */
    public static final String GROK_ARG_PROMPT = "-p";
    /** --output-format：输出格式。 */
    public static final String GROK_ARG_OUTPUT_FORMAT = "--output-format";
    /** --output-format streaming-json：NDJSON 事件流（text/thought/end/error）。 */
    public static final String GROK_FORMAT_STREAMING_JSON = "streaming-json";
    /** -m/--model：~/.grok/config.toml 的 [model."..."] profile 名（非上游模型 id）。 */
    public static final String GROK_ARG_MODEL = "-m";
    /** --reasoning-effort：推理强度（low/medium/high）。 */
    public static final String GROK_ARG_REASONING_EFFORT = "--reasoning-effort";
    /** -s/--session-id：创建或恢复命名 headless 会话（首轮预分配 UUID 用）。 */
    public static final String GROK_ARG_SESSION_ID = "-s";
    /** -r/--resume：恢复既有会话。 */
    public static final String GROK_ARG_RESUME = "-r";
    /**
     * --always-approve：自动批准工具执行。headless 流式模式无审批交互通道，
     * 无论前端 permissionMode 如何都必带（有意差异:与 opencode 的 --auto 条件注入不同,
     * grok headless 不带此 flag 会卡在 TUI 审批提示）。
     */
    public static final String GROK_ARG_ALWAYS_APPROVE = "--always-approve";

    // ── Kimi Code CLI 参数（非交互模式,对齐 kimi-command 官方参考） ─────────────
    // 真实命令：kimi --output-format stream-json --prompt <text> [--model <alias>] [--session <id>]
    // 非 -p 模式默认 auto 权限;thinking 不写入 JSONL(官方限制,思考区暂不支持)。
    // 会话存 $KIMI_CODE_HOME/sessions/<workDirKey>/<sessionId>/。

    /** --prompt/-p：单条 prompt 非交互执行。 */
    public static final String KIMI_ARG_PROMPT = "--prompt";
    /** --output-format：输出格式（仅可与 --prompt 组合）。 */
    public static final String KIMI_ARG_OUTPUT_FORMAT = "--output-format";
    /** --output-format stream-json：JSONL 事件流（assistant/tool/meta role 行）。 */
    public static final String KIMI_FORMAT_STREAM_JSON = "stream-json";
    /** --model/-m：模型别名（省略用 config default_model）。 */
    public static final String KIMI_ARG_MODEL = "--model";
    /** --session/-S：续接指定 session id（--continue 互斥）。 */
    public static final String KIMI_ARG_SESSION = "--session";

    // ── Pi CLI 参数（print + JSON 事件流模式,对齐 pi.dev/docs usage/json） ──────
    // 真实命令：pi --print --mode json "<positional-message>" [--model <pattern>]
    //          [--session-id <id>] [--thinking off..max]
    // 会话存 ~/.pi/agent/sessions/（JSONL 公开格式）;MCP 故意不内置（设计原则）。

    /** --print/-p：打印响应后退出。 */
    public static final String PI_ARG_PRINT = "--print";
    /** --model：模型 pattern 或 ID（支持 provider/id 与 :thinking 简写）。 */
    public static final String PI_ARG_MODEL = "--model";
    /** --mode json：全部会话事件以 JSON lines 输出。 */
    public static final String PI_ARG_MODE = "--mode";
    /** --mode 值：json 事件流。 */
    public static final String PI_FORMAT_JSON = "json";
    /** --session-id：使用指定会话文件或部分 UUID。 */
    public static final String PI_ARG_SESSION_ID = "--session-id";
    /** --thinking：思考级别（off/minimal/low/medium/high/xhigh/max），由 reasoningEffort 映射。 */
    public static final String PI_ARG_THINKING = "--thinking";

    // ── Sandbox 模式值 ─────────────────────────────────────────────────────────

    public static final String SANDBOX_READ_ONLY = "read-only";
    public static final String SANDBOX_WORKSPACE_WRITE = "workspace-write";
    public static final String SANDBOX_DANGER_FULL_ACCESS = "danger-full-access";

    public static final Set<String> VALID_SANDBOX_MODES = Set.of(
            SANDBOX_READ_ONLY, SANDBOX_WORKSPACE_WRITE, SANDBOX_DANGER_FULL_ACCESS
    );

    // ── 权限模式值（Claude CLI 专用子集） ──────────────────────────────────────
    // 完整定义见 CommonConstants.PERMISSION_MODE_*；此处保留子集用于 CLI 参数校验。

    public static final Set<String> VALID_PERMISSION_MODES = Set.of(
            CommonConstants.PERMISSION_MODE_DEFAULT, CommonConstants.PERMISSION_MODE_ACCEPT_EDITS, CommonConstants.PERMISSION_MODE_PLAN
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
    public static final String ENV_OPENCODE_USE_STDIN = "OPENCODE_USE_STDIN";
    /**
     * OpenCode 运行时 inline 配置覆盖(smoke-test 2026-07-02 实测):
     * opencode 把此 env 的 JSON 内容与真实 ~/.config/opencode/opencode.json **合并**(非替换),
     * 故可注入 {@code melon_gateway} 聚合 server 并逐个 {@code "<realId>":{"enabled":false}} 禁真实 server,
     * HOME/XDG 保持真实 → 零临时 home、零配置文件复制(对齐 Codex {@code -c} 方案)。
     */
    public static final String ENV_OPENCODE_CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT";
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

    /** Claude CLI 进程身份标识，影响 session 文件 entrypoint 字段。 */
    public static final String ENV_CLAUDE_CODE_ENTRYPOINT = "CLAUDE_CODE_ENTRYPOINT";
    /** entrypoint 值：CLI 模式（由插件启动的 claude -p 子进程）。 */
    public static final String ENV_CLAUDE_ENTRYPOINT_CLI = "cli";
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
