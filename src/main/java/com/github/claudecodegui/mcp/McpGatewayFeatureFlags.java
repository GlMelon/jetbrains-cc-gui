package com.github.claudecodegui.mcp;

import com.github.claudecodegui.settings.CodemossSettingsService;

/**
 * Feature gates for the MCP Gateway rollout.
 * <p>
 * 三层门禁(AND 关系),默认全部开启(catalog 预热消除首次冷连接延迟,见 commit 8a44250d):
 * <ol>
 *   <li>JVM -D 总开关 {@code mcpGateway.enabled}:最高优先级运维紧急关停</li>
 *   <li>UI user 开关(行为菜单,默认 true,经 {@code CodemossSettingsService} 持久化):
 *       用户主动关闭后回退直连 MCP,gateway Node 进程被主动停止</li>
 *   <li>JVM -D 子开关 {@code cli.enabled}:仅关 CLI 路径,以总开关为前提(SDK 路径随 SDK 模式移除)</li>
 * </ol>
 */
public final class McpGatewayFeatureFlags {
    /**
     * 测试专用覆盖钩子。非 null 时绕过 {@link CodemossSettingsService} 读取(单测环境无
     * Application 实例,无法走真实 getInstance 路径)。生产路径恒为 null,无副作用。
     */
    static volatile Boolean testUserEnabledOverride;

    private McpGatewayFeatureFlags() {
    }

    public static boolean isGatewayEnabled() {
        return Boolean.parseBoolean(System.getProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "true"));
    }

    /**
     * 用户在 UI 行为菜单的 MCP Gateway 开关(默认 true)。读取持久化的 config.json;
     * Application 未就绪或读取异常时默认 true(对标其他 -D 兜底语义,避免误关)。
     */
    public static boolean isUserEnabled() {
        if (testUserEnabledOverride != null) {
            return testUserEnabledOverride;
        }
        try {
            return CodemossSettingsService.getInstance().getMcpGatewayEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isCliEnabled() {
        return isGatewayEnabled()
                && isUserEnabled()
                && Boolean.parseBoolean(System.getProperty(McpGatewayConstants.FEATURE_CLI_ENABLED, "true"));
    }

    /**
     * gateway 是否处于活跃工作状态。供预热/重载等"不分运行时路径"的入口
     * ({@code refreshConfig} 预热与 MCP 变更重载、{@code BridgePreloader} 项目打开预热)做 gate:
     * 只要 gateway 会被 provider 运行时用到,就应预热、就应在 MCP 增删停时重载。
     * SDK 模式移除后仅剩 CLI 路径,故等价于 {@link #isCliEnabled()}(保留独立方法名以维持
     * 调用方语义清晰,避免每个调用点都内联 isCliEnabled)。
     */
    public static boolean isGatewayActive() {
        return isCliEnabled();
    }
}
