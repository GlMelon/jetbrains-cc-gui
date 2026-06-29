package com.github.claudecodegui.common;

/**
 * 全局公共常量集中管理类。
 * <p>
 * 将散布在各处的硬编码字符串值统一抽取到此文件，便于集中维护和修改。
 * 所有常量按功能分组，每组用注释分隔。
 * <p>
 * 使用方式：在需要引用硬编码值的地方，使用 {@code CommonConstants.XXX} 替代字符串字面量。
 * 修改某个值时只需在此处修改一处即可全局生效。
 *
 * @see com.github.claudecodegui.session.SessionState
 * @see com.github.claudecodegui.session.RawMessageHelper
 */
public final class CommonConstants {

    private CommonConstants() {
        // 工具类禁止实例化
    }

    // ===== Provider 名称 =====
    // 用于判断当前使用的 AI 提供者（Claude 或 Codex）

    /** Claude AI 提供者标识 */
    public static final String PROVIDER_CLAUDE = "claude";
    /** Codex AI 提供者标识 */
    public static final String PROVIDER_CODEX = "codex";
    /** OpenCode AI 提供者标识 */
    public static final String PROVIDER_OPENCODE = "opencode";

    // ===== 权限模式 =====
    // 控制工具调用（文件写入、命令执行等）的自动审批策略

    /** 默认权限模式：每次工具调用都需要用户确认 */
    public static final String PERMISSION_MODE_DEFAULT = "default";
    /** 计划模式：所有工具调用被拒绝，仅允许只读操作 */
    public static final String PERMISSION_MODE_PLAN = "plan";
    /** 自动接受编辑：文件编辑类操作自动批准，其他操作需确认 */
    public static final String PERMISSION_MODE_ACCEPT_EDITS = "acceptEdits";
    /** 自动编辑模式：与 acceptEdits 类似的自动编辑策略 */
    public static final String PERMISSION_MODE_AUTO_EDIT = "autoEdit";
    /** 绕过所有权限检查：所有操作自动批准（仅限受信环境） */
    public static final String PERMISSION_MODE_BYPASS = "bypassPermissions";

    // ===== 调用模式 =====
    // 控制 AI 会话的底层通信方式

    /** SDK 模式：通过 Node.js SDK Bridge 与 AI 服务通信 */
    public static final String INVOCATION_MODE_SDK = "sdk";
    /** CLI 模式：通过命令行进程（claude/codex CLI）与 AI 服务通信 */
    public static final String INVOCATION_MODE_CLI = "cli";

    // ===== 默认配置值 =====
    // 新会话创建时使用的默认参数

    /** 默认 AI 模型 */
    public static final String DEFAULT_MODEL = "claude-role-sonnet";
    /** 默认 Codex 模型(用户 ~/.codex/config.toml 未配置 model 时的回退,即官方 OpenAI 默认) */
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.3-codex";
    /** 默认权限模式 */
    public static final String DEFAULT_PERMISSION_MODE = "acceptEdits";
    /** 默认推理努力程度（thinking depth） */
    public static final String DEFAULT_REASONING_EFFORT = "high";
    /** 默认 AI 提供者 */
    public static final String DEFAULT_PROVIDER = "claude";
    /** 默认标签页 ID */
    public static final String DEFAULT_TAB_ID = "default";
    /**
     * 默认模型上下文窗口大小（token 数）。
     * <p>contextWindow 缺省时的权威 fallback —— 全局 SSOT，消除散落硬编码 200_000。
     * <p>引用方：DefaultModelCapabilityResolver / ModelRegistryConfig / ModelProviderHandler /
     * ModelRegistryService / CodemossSettingsService / ReadOnlyDefaultModels。
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    /**
     * 长上下文（1M）窗口阈值（token 数）。
     * <p>判定 supports1MContext / 1M 切换 / 配置校验的边界 —— 全局 SSOT。
     * <p>注：模型名 [Nm] 后缀解析中的 {@code value * 1_000_000} 是 mega 单位换算因子，
     * 非本阈值语义，不引用此常量。
     */
    public static final int ONE_MILLION_CONTEXT_WINDOW = 1_000_000;

    // ===== JSON 消息类型 =====
    // SDK 回调消息的 type 字段值，用于路由到对应的处理方法

    /** 用户消息类型 */
    public static final String MSG_TYPE_USER = "user";
    /** AI 助手消息类型 */
    public static final String MSG_TYPE_ASSISTANT = "assistant";
    /** 工具调用消息类型（AI 请求执行工具） */
    public static final String MSG_TYPE_TOOL_USE = "tool_use";
    /** 工具结果消息类型（工具执行完成后返回结果） */
    public static final String MSG_TYPE_TOOL_RESULT = "tool_result";
    /** 思考状态消息类型（AI 正在推理中） */
    public static final String MSG_TYPE_THINKING = "thinking";
    /** 文本内容消息类型（非流式完整文本块） */
    public static final String MSG_TYPE_TEXT = "text";
    /** 图片内容消息类型 */
    public static final String MSG_TYPE_IMAGE = "image";
    /** 系统消息类型(前端会话消息:系统提示/通知,与 ClaudeSession.Message.Type.SYSTEM 对齐) */
    public static final String MSG_TYPE_SYSTEM = "system";
    /** 错误消息类型(前端会话消息:错误反馈,与 ClaudeSession.Message.Type.ERROR 对齐) */
    public static final String MSG_TYPE_ERROR = "error";

    // ===== 内容块类型 =====
    // message.content 数组中各内容块的 type 字段值

    /** 文本内容块 */
    public static final String BLOCK_TYPE_TEXT = "text";
    /** 思考内容块（扩展思考过程） */
    public static final String BLOCK_TYPE_THINKING = "thinking";
    /** 工具调用内容块 */
    public static final String BLOCK_TYPE_TOOL_USE = "tool_use";
    /** 服务端工具调用内容块（Anthropic 服务端执行的工具，如 web 搜索） */
    public static final String BLOCK_TYPE_SERVER_TOOL_USE = "server_tool_use";
    /** 工具结果内容块 */
    public static final String BLOCK_TYPE_TOOL_RESULT = "tool_result";
    /** 输入文本内容块（Codex 格式） */
    public static final String BLOCK_TYPE_INPUT_TEXT = "input_text";
    /** 输出文本内容块（Codex 格式） */
    public static final String BLOCK_TYPE_OUTPUT_TEXT = "output_text";
    /** 图片内容块 */
    public static final String BLOCK_TYPE_IMAGE = "image";
    /** 图片源编码类型：base64 */
    public static final String IMAGE_SOURCE_BASE64 = "base64";

    // ===== JSON 键名 =====
    // 消息 JSON 结构中常用的字段名，统一管理避免拼写错误

    /** JSON 字段：类型 */
    public static final String JSON_KEY_TYPE = "type";
    /** JSON 字段：角色（user/assistant） */
    public static final String JSON_KEY_ROLE = "role";
    /** JSON 字段：内容数组 */
    public static final String JSON_KEY_CONTENT = "content";
    /** JSON 字段：文本内容 */
    public static final String JSON_KEY_TEXT = "text";
    /** JSON 字段：思考内容 */
    public static final String JSON_KEY_THINKING = "thinking";
    /** JSON 字段：消息对象 */
    public static final String JSON_KEY_MESSAGE = "message";
    /** JSON 字段：用量统计 */
    public static final String JSON_KEY_USAGE = "usage";
    /** JSON 字段：唯一标识 */
    public static final String JSON_KEY_ID = "id";
    /** JSON 字段：名称 */
    public static final String JSON_KEY_NAME = "name";
    /** JSON 字段：输入参数 */
    public static final String JSON_KEY_INPUT = "input";
    /** JSON 字段：是否为错误 */
    public static final String JSON_KEY_IS_ERROR = "is_error";
    /** JSON 字段：错误信息 */
    public static final String JSON_KEY_ERROR = "error";
    /** JSON 字段：状态 */
    public static final String JSON_KEY_STATUS = "status";
    /** JSON 字段：消息 UUID */
    public static final String JSON_KEY_UUID = "uuid";
    /** JSON 字段：工具调用关联 ID */
    public static final String JSON_KEY_TOOL_USE_ID = "tool_use_id";
    /** JSON 字段：是否为元消息 */
    public static final String JSON_KEY_IS_META = "isMeta";

    // ===== 特殊内容标记 =====

    /** 工具结果消息在聊天界面中的占位符显示文本 */
    public static final String TOOL_RESULT_PLACEHOLDER = "[tool_result]";

    // ===== 命令消息标签 =====
    // SDK 返回的消息中可能包含的 XML 标签，用于过滤内部命令消息

    /** 命令消息开始标签 */
    public static final String TAG_COMMAND_MESSAGE_OPEN = "<command-message>";
    /** 命令消息结束标签 */
    public static final String TAG_COMMAND_MESSAGE_CLOSE = "</command-message>";
    /** 命令名称标签 */
    public static final String TAG_COMMAND_NAME = "<command-name>";
    /** 本地命令标准输出标签 */
    public static final String TAG_LOCAL_COMMAND_STDOUT = "<local-command-stdout>";
    /** 本地命令标准错误标签 */
    public static final String TAG_LOCAL_COMMAND_STDERR = "<local-command-stderr>";
    /** 命令参数标签 */
    public static final String TAG_COMMAND_ARGS = "<command-args>";

    // ===== JavaScript 函数名 =====
    // 后端调用前端 WebView 中的 JavaScript 函数名

    /** 权限模式变更通知 */
    public static final String JS_ON_MODE_RECEIVED = "window.onModeReceived";
    /** 模型确认通知 */
    public static final String JS_ON_MODEL_CONFIRMED = "window.onModelConfirmed";
    /** 显示错误信息 */
    public static final String JS_SHOW_ERROR = "window.showError";
    /** 后端通知频道 */
    public static final String JS_BACKEND_NOTIFICATION = "backend_notification";
    /** 添加错误消息到聊天 */
    public static final String JS_ADD_ERROR_MESSAGE = "addErrorMessage";

    // ===== JavaScript 布尔值 =====

    /** JavaScript 布尔值：真 */
    public static final String JS_TRUE = "true";
    /** JavaScript 布尔值：假 */
    public static final String JS_FALSE = "false";

    // ===== 终端协议 =====

    /** 终端选区协议前缀，用于标识来自终端的上下文内容 */
    public static final String TERMINAL_PROTOCOL = "terminal://";

    // ===== 系统属性键 =====

    /** 权限模式持久化属性键 */
    public static final String PROP_PERMISSION_MODE = "claude.code.permission.mode";

    // ===== 环境变量名 (Anthropic / Claude) =====
    // 权威定义：cli 包与 handler 包统一引用此处，消除与 CliConstants 的历史重复。

    /** 环境变量：指定默认使用的模型 */
    public static final String ENV_ANTHROPIC_MODEL = "ANTHROPIC_MODEL";
    /** 环境变量：Opus 模型覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_OPUS_MODEL = "ANTHROPIC_DEFAULT_OPUS_MODEL";
    /** 环境变量：Haiku 模型覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_HAIKU_MODEL = "ANTHROPIC_DEFAULT_HAIKU_MODEL";
    /** 环境变量：Sonnet 模型覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_SONNET_MODEL = "ANTHROPIC_DEFAULT_SONNET_MODEL";
    /** 环境变量：Fable 模型覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_FABLE_MODEL = "ANTHROPIC_DEFAULT_FABLE_MODEL";
    /** 环境变量：小快模型（Haiku 的别名通道） */
    public static final String ENV_ANTHROPIC_SMALL_FAST_MODEL = "ANTHROPIC_SMALL_FAST_MODEL";
    /** 环境变量：模型能力覆盖（逗号分隔的能力 token） */
    public static final String ENV_ANTHROPIC_MODEL_CAPABILITIES = "ANTHROPIC_MODEL_CAPABILITIES";
    /** 环境变量：Opus 模型能力覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_OPUS_MODEL_CAPABILITIES = "ANTHROPIC_DEFAULT_OPUS_MODEL_CAPABILITIES";
    /** 环境变量：Fable 模型能力覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_FABLE_MODEL_CAPABILITIES = "ANTHROPIC_DEFAULT_FABLE_MODEL_CAPABILITIES";
    /** 环境变量：Sonnet 模型能力覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_SONNET_MODEL_CAPABILITIES = "ANTHROPIC_DEFAULT_SONNET_MODEL_CAPABILITIES";
    /** 环境变量：小快模型能力覆盖 */
    public static final String ENV_ANTHROPIC_SMALL_FAST_MODEL_CAPABILITIES = "ANTHROPIC_SMALL_FAST_MODEL_CAPABILITIES";
    /** 环境变量：Haiku 模型能力覆盖 */
    public static final String ENV_ANTHROPIC_DEFAULT_HAIKU_MODEL_CAPABILITIES = "ANTHROPIC_DEFAULT_HAIKU_MODEL_CAPABILITIES";
    /** 环境变量：API key helper 脚本 */
    public static final String ENV_ANTHROPIC_API_KEY_HELPER = "ANTHROPIC_API_KEY_HELPER";

    // ===== 代理环境变量名 =====
    // HTTP/HTTPS 代理配置

    /** 环境变量：HTTP 代理地址 */
    public static final String ENV_HTTP_PROXY = "HTTP_PROXY";
    /** 环境变量：HTTPS 代理地址 */
    public static final String ENV_HTTPS_PROXY = "HTTPS_PROXY";
    /** 环境变量：通用代理地址 */
    public static final String ENV_ALL_PROXY = "ALL_PROXY";
    /** 环境变量：不使用代理的地址列表 */
    public static final String ENV_NO_PROXY = "NO_PROXY";

    // ===== Windows 主目录环境变量 =====

    /** 环境变量：Windows 主目录驱动器（如 C:） */
    public static final String ENV_HOMEDRIVE = "HOMEDRIVE";
    /** 环境变量：Windows 主目录路径（如 \Users\xxx） */
    public static final String ENV_HOMEPATH = "HOMEPATH";

    // ===== 哨兵值 =====
    // SDK 输出中用于表示无效或未定义的特殊字符串

    /** 哨兵值：SDK 返回的 "undefined" 字符串 */
    public static final String UNDEFINED = "undefined";
    /** 哨兵值：SDK 返回的 "null" 字符串 */
    public static final String NULL_SENTINEL = "null";

    // ===== 配置文件名 =====
    // CLI 工具的配置文件和目录名

    /** Claude CLI 配置目录名 */
    public static final String DIR_CLAUDE = ".claude";
    /** Claude CLI 设置文件名 */
    public static final String FILE_SETTINGS_JSON = "settings.json";
    /** Codex CLI 配置目录名 */
    public static final String DIR_CODEX = ".codex";
    /** Codex CLI 认证文件名 */
    public static final String FILE_AUTH_JSON = "auth.json";
    /** OpenCode CLI 配置目录名 */
    public static final String DIR_OPENCODE = ".config/opencode";
    /** OpenCode CLI 配置文件名 */
    public static final String FILE_OPENCODE_JSON = "opencode.json";

    // ===== TOML 配置键 =====
    // Claude CLI TOML 配置文件中的字段名

    /** TOML 键：模型名称 */
    public static final String TOML_KEY_MODEL = "model";
    /** TOML 键：环境变量配置 */
    public static final String TOML_KEY_ENV = "env";
    /** TOML 键：模型提供者 */
    public static final String TOML_KEY_MODEL_PROVIDER = "model_provider";
    /** TOML 键：模型提供者列表 */
    public static final String TOML_KEY_MODEL_PROVIDERS = "model_providers";
    /** TOML 键：API 基础 URL */
    public static final String TOML_KEY_BASE_URL = "base_url";

    // ===== CLI 设置键 =====
    // 插件设置 JSON 中的字段名

    /** 设置键：权限对话框超时秒数 */
    public static final String SETTING_PERMISSION_TIMEOUT = "permissionDialogTimeoutSeconds";
    /** 设置键：Codex 沙箱模式 */
    public static final String SETTING_CODEX_SANDBOX_MODE = "codexSandboxMode";
    /** 设置键：Claude 环境变量配置 */
    public static final String SETTING_CLAUDE_ENV = "claudeEnv";

    // ===== 会话状态值（状态栏 UI 显示状态） =====
    // 与 MSG_TYPE_THINKING/ERROR 的 wire 值重叠，但语义为整体会话状态（非消息类型）。

    /** 会话状态：就绪 */
    public static final String SESSION_STATUS_READY = "ready";
    /** 会话状态：思考中 */
    public static final String SESSION_STATUS_THINKING = "thinking";
    /** 会话状态：生成中 */
    public static final String SESSION_STATUS_GENERATING = "generating";
    /** 会话状态：等待中 */
    public static final String SESSION_STATUS_WAITING = "waiting";
    /** 会话状态：成功 */
    public static final String SESSION_STATUS_SUCCESS = "success";
    /** 会话状态：错误 */
    public static final String SESSION_STATUS_ERROR = "error";

    // ===== MCP 传输类型 =====
    // MCP 服务器配置的传输协议类型（stdio/http/sse）

    /** MCP 服务器传输类型：标准输入输出 */
    public static final String MCP_TRANSPORT_STDIO = "stdio";
    /** MCP 服务器传输类型：HTTP */
    public static final String MCP_TRANSPORT_HTTP = "http";
    /** MCP 服务器传输类型：SSE */
    public static final String MCP_TRANSPORT_SSE = "sse";
    /** MCP 服务器连接状态：已连接 */
    public static final String MCP_STATUS_CONNECTED = "connected";

    // ===== 前端请求动作类型（前端→后端 message type） =====
    // 前端通过 JSON message 的 type 字段标识请求的动作，由对应 Handler 路由处理。

    public static final String REQUEST_TYPE_PERMISSION_DECISION = "permission_decision";
    public static final String REQUEST_TYPE_ASK_USER_QUESTION_RESPONSE = "ask_user_question_response";
    public static final String REQUEST_TYPE_PLAN_APPROVAL_RESPONSE = "plan_approval_response";
    public static final String REQUEST_TYPE_REWIND_FILES = "rewind_files";
    public static final String REQUEST_TYPE_UNDO_FILE_CHANGES = "undo_file_changes";
    public static final String REQUEST_TYPE_UNDO_ALL_FILE_CHANGES = "undo_all_file_changes";
    public static final String REQUEST_TYPE_SAVE_MARKDOWN = "save_markdown";
    public static final String REQUEST_TYPE_SAVE_JSON = "save_json";
    public static final String REQUEST_TYPE_CREATE_NEW_TAB = "create_new_tab";
    public static final String REQUEST_TYPE_ENHANCE_PROMPT = "enhance_prompt";
}
