package com.github.claudecodegui.cli.kimi.acp;

/**
 * kimi ACP (Agent Client Protocol) 通道协议常量 SSOT。
 *
 * <p>kimi {@code acp} 子命令以 ACP server over stdio 运行,NDJSON framing,
 * JSON-RPC 2.0 消息。本类集中管理 method 名、{@code session/update} 变体、
 * config id、thinking 取值与错误码,避免散落字面量。
 *
 * <p>协议事实基于 0.38.0–0.41.0 实测 + MoonshotAI/kimi-code {@code packages/acp-server}
 * (agent-core-v2)源码级确认。注意:0.38.0 起 {@code kimi acp} 默认路由到 acp-server
 * (acp-native),旧 {@code packages/acp-adapter} 仅在 {@code KIMI_CODE_LEGACY_FLAG}
 * 启用时使用,0.40.0 起 acp-adapter 已删除:
 * <ul>
 *   <li>{@code agent_thought_chunk}:思考内容一等公民通道(stream-json 通道不透出 thinking,
 *       ACP 通道透出,纯增量 delta,需 {@code session/set_config_option} 显式开启 thinking);</li>
 *   <li>{@code tool_call}/{@code tool_call_update}:懒创建,update.content 为 REPLACE 语义;</li>
 *   <li>{@code session_info_update}:0.38 实测带 title(接 CliSessionTitleService);</li>
 *   <li>{@code usage_update}:上下文窗口占用快照 {@code {used, size}}(used=当前上下文 token 数,
 *       size=模型上下文上限,无 cost、无单轮 input/output 拆分),turn settle 后 one-shot 发出。
 *       <b>0.38.0 发布包实测已发出</b>(npm dist 含 emitUsageUpdate;git tag 0.38.0 的
 *       acp-server/session.ts 源码未见调用,发布构建与 tag 源码有差异,以实测为准);</li>
 *   <li>未登录返回错误码 {@code -32000}。</li>
 * </ul>
 *
 * @see com.github.claudecodegui.cli.kimi.acp.KimiAcpCliSession
 * @see com.github.claudecodegui.cli.kimi.acp.KimiAcpStreamParser
 */
final class KimiAcpProtocol {

    private KimiAcpProtocol() {
    }

    // ── JSON-RPC method 名 ───────────────────────────────────────────────────

    static final String METHOD_INITIALIZE = "initialize";
    static final String METHOD_SESSION_NEW = "session/new";
    static final String METHOD_SESSION_LOAD = "session/load";
    static final String METHOD_SESSION_PROMPT = "session/prompt";
    static final String METHOD_SESSION_UPDATE = "session/update";
    static final String METHOD_SET_CONFIG_OPTION = "session/set_config_option";
    static final String METHOD_REQUEST_PERMISSION = "session/request_permission";

    // ── session/update 变体(sessionUpdate 字段值) ────────────────────────────

    /** 思考增量(纯 delta,需客户端拼接)。content:{type:text,text}。 */
    static final String UPDATE_AGENT_THOUGHT_CHUNK = "agent_thought_chunk";
    /** 正文增量。content:{type:text,text}。 */
    static final String UPDATE_AGENT_MESSAGE_CHUNK = "agent_message_chunk";
    /** 工具调用(懒创建:首条 delta 即创建 pending)。 */
    static final String UPDATE_TOOL_CALL = "tool_call";
    /** 工具调用更新(content 为 REPLACE 语义,status:in_progress/completed/failed)。 */
    static final String UPDATE_TOOL_CALL_UPDATE = "tool_call_update";
    /** 会话信息更新(0.38 实测带 title)。 */
    static final String UPDATE_SESSION_INFO = "session_info_update";
    /** 可用斜杠命令列表(每 session 建立后推一条,忽略)。 */
    static final String UPDATE_AVAILABLE_COMMANDS = "available_commands_update";
    /** 用户消息增量(仅 session/load 重放历史时出现,门控丢弃)。 */
    static final String UPDATE_USER_MESSAGE_CHUNK = "user_message_chunk";
    /**
     * 上下文窗口占用快照(turn settle 后 one-shot;0.38.0 发布包实测已发出)。
     * 字段:used(当前上下文 token 数)、size(模型上下文上限);无 cost、无单轮
     * input/output 拆分。更低版本是否发出未逐一实测,解析侧按缺字段防御
     * (总则六·健壮性),无需独立版本门禁。
     */
    static final String UPDATE_USAGE = "usage_update";

    // ── config option ────────────────────────────────────────────────────────

    /** configOption id:思考档位(category=thought_level)。默认 off,需显式开启。0.29.0+ 支持。 */
    static final String CONFIG_ID_THINKING = "thinking";
    /** configOption 分组:思考档位所在 category(set_config_option 校验按模型目录动态定)。 */
    static final String CONFIG_CATEGORY_THOUGHT_LEVEL = "thought_level";

    // ── thinking 取值(thought_level) ─────────────────────────────────────────

    /**
     * thinking 取值。
     *
     * <p><b>协议事实(0.38 dist/main.mjs 源码级确认):合法值是模型动态的</b>——
     * {@code set_config_option} 校验 {@code ["off", ...当前模型 supportEfforts]}
     * (模型无 effort 目录时为 {@code ["off","on"]};alwaysThinking 时为 {@code ["on"]})。
     * {@code "on"} 是万能别名(自动解析为模型 {@code defaultThinkingEffort});具体
     * effort 字面量("medium" 等)仅当模型 supportEfforts 包含它才合法。
     * <b>权威合法值列表在 session/new / session/load 响应的 configOptions 里</b>
     * (type=select、category=thought_level 的项,含 options[].value 与 currentValue),
     * 客户端应从那里协商而非硬编码字面量(实测 k3 发 "medium" 即被拒)。
     */
    static final String THINKING_OFF = "off";
    static final String THINKING_ON = "on";
    static final String THINKING_LOW = "low";
    static final String THINKING_MEDIUM = "medium";
    static final String THINKING_HIGH = "high";
    static final String THINKING_MAX = "max";

    // ── 错误码 ───────────────────────────────────────────────────────────────

    /** 未登录:session/new/load/prompt 在未认证时返回。客户端应引导 terminal login 后 authenticate 重试。 */
    static final int ERROR_NOT_LOGGED_IN = -32000;

    // ── 协议字段 ─────────────────────────────────────────────────────────────

    static final String JSONRPC_VERSION = "2.0";
    static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    static final String FIELD_SESSION_ID = "sessionId";
    static final String FIELD_CWD = "cwd";
    static final String FIELD_MCP_SERVERS = "mcpServers";
    static final String FIELD_PROMPT = "prompt";
    static final String FIELD_CONFIG_ID = "configId";
    static final String FIELD_VALUE = "value";
    static final String FIELD_STOP_REASON = "stopReason";
    /** session/new / session/load 响应字段:configOptions 数组(select 项含合法值目录)。 */
    static final String FIELD_CONFIG_OPTIONS = "configOptions";
    /** configOption(select)内字段:当前生效值。 */
    static final String FIELD_CURRENT_VALUE = "currentValue";
    /** configOption(select)内字段:合法值列表 [{value,name}](取值复用 {@link #FIELD_VALUE})。 */
    static final String FIELD_OPTIONS = "options";
    /** configOption 内字段:选项分组 category。 */
    static final String FIELD_CATEGORY = "category";
    /** configOption 内字段:选项 id(thinking)。 */
    static final String FIELD_ID = "id";
}
