package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.settings.CodemossSettingsService;

/**
 * CLI 长驻会话模式的三层功能门禁(回退开关)。
 * <p>
 * 仿 {@link com.github.claudecodegui.mcp.McpGatewayFeatureFlags},三层 AND 关系,默认全开:
 * <ol>
 *   <li>JVM -D 总开关 {@code cliPersistent.enabled}:最高优先级运维紧急关停,
 *       关停后全部 provider 回 one-shot 纯路径;</li>
 *   <li>UI user 开关(行为菜单,默认 true,经 {@code CodemossSettingsService} 持久化):
 *       用户主动关闭后长驻进程被回收,后续消息走 one-shot;</li>
 *   <li>JVM -D 子开关 {@code cliPersistent.claude.enabled}:仅关 claude 长驻路径
 *       (Phase 1 唯一 provider;Phase 2/3 各 provider 仿此增加子开关)。</li>
 * </ol>
 */
public final class CliPersistentFeatureFlags {

    /** -D 总开关(默认 true)。 */
    public static final String FEATURE_ENABLED_KEY = "cliPersistent.enabled";
    /** -D claude 子开关(默认 true)。 */
    public static final String FEATURE_CLAUDE_ENABLED_KEY = "cliPersistent.claude.enabled";

    /**
     * 测试专用覆盖钩子。非 null 时绕过 {@link CodemossSettingsService} 读取(单测环境无
     * Application 实例,无法走真实 getInstance 路径)。生产路径恒为 null,无副作用。
     */
    static volatile Boolean testUserEnabledOverride;

    private CliPersistentFeatureFlags() {
    }

    public static boolean isSystemEnabled() {
        return Boolean.parseBoolean(System.getProperty(FEATURE_ENABLED_KEY, "true"));
    }

    /**
     * 用户在 UI 行为菜单的长驻会话开关(默认 true)。读取持久化的 config.json;
     * Application 未就绪或读取异常时默认 true(对标其他 -D 兜底语义,避免误关)。
     */
    public static boolean isUserEnabled() {
        if (testUserEnabledOverride != null) {
            return testUserEnabledOverride;
        }
        try {
            return CodemossSettingsService.getInstance().getCliPersistentEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** claude 长驻路径是否启用(总开关 AND user 开关 AND claude 子开关)。 */
    public static boolean isClaudeEnabled() {
        return isSystemEnabled()
                && isUserEnabled()
                && Boolean.parseBoolean(System.getProperty(FEATURE_CLAUDE_ENABLED_KEY, "true"));
    }
}
