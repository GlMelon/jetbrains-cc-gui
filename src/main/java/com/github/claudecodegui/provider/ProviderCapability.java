package com.github.claudecodegui.provider;

/**
 * Provider 维度能力声明（F1 capability descriptor 地基）。
 *
 * <p>采用与 {@code com.github.claudecodegui.handler.history.HistoryCapability} 一致的<b>声明式能力模式</b>：
 * {@link ProviderAdapter#capabilities()} 由各 Provider 自描述其支持的横切能力维度，
 * {@link ProviderRegistry} 聚合并提供查询（{@code hasCapability}/{@code capabilities}/
 * {@code providersWithCapability}），业务层据此决策，消除核心路由中的 Provider {@code if/else} 分派。
 *
 * <p>层次区分（三套正交 capability，各管各的域，不互相替代）：
 * <ul>
 *   <li>{@code ProviderCapability}（本枚举）—— Provider 维度的横切能力；</li>
 *   <li>{@code HistoryCapability} —— 历史域内细粒度能力（DELETE/ARCHIVE），历史操作细分的单一真相；</li>
 *   <li>{@code ModelCapabilityResolver} —— 模型级能力（reasoning levels）。</li>
 * </ul>
 *
 * <p>本枚举的 {@link #HISTORY} 为粗粒度（“支持历史读取”），删除/归档细分继续由
 * {@code HistoryCapability} 单一管理，避免双源真相漂移。
 */
public enum ProviderCapability {
    /** CLI 子进程会话。 */
    CLI_SESSION,

    /** 流式增量输出。 */
    STREAMING,

    /** 推理思考区展示（reasoning / thinking）。 */
    REASONING_THINKING,

    /** 历史会话/消息读取（删除/归档细分见 HistoryCapability）。 */
    HISTORY,

    /** Skill 查看/编辑。 */
    SKILLS,

    /** MCP 服务器/网关。 */
    MCP
}
