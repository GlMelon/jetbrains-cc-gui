package com.github.claudecodegui.cli;

/**
 * CLI 会话工厂接口(总则五·开闭)。
 * <p>
 * 每个 provider 一个实现,声明自己的 provider 路由键并提供 {@link CliSession} 创建能力。
 * {@link CliSessionManager} 持有 {@code Map<provider, CliSessionFactory>} 装配注册,
 * 新增 CLI provider 只需新增一个工厂实现 + 一行注册,createSession 路由代码不变。
 * <p>
 * 范式对齐 {@link com.github.claudecodegui.session.runtime.SessionRuntime}(
 * provider() 路由键 + 注册表 Map 查表),取代原先 createSession 内的 provider switch。
 */
public interface CliSessionFactory {

    /**
     * 返回此工厂对应的 provider 标识(如 {@code "claude"}/{@code "codex"},
     * 对齐 {@link com.github.claudecodegui.cli.common.CliConstants#PROVIDER_CLAUDE} 等)。
     */
    String provider();

    /**
     * 为指定 tab 创建 CLI 会话实例。
     *
     * @param tabId 标签页 ID
     * @return 新的 CliSession 实例
     */
    CliSession create(String tabId);
}
