package com.github.claudecodegui.mcp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MCP Gateway 生产环境默认开启(catalog 预热消除首次冷连接延迟,见 commit 8a44250d)。
 *
 * <p>三层门禁(AND 关系):
 * <ol>
 *   <li><b>-D 总开关</b> {@code -DmcpGateway.enabled=false} —— 最高优先级运维紧急关停,
 *       关闭后 CLI/SDK 子开关一律失效</li>
 *   <li><b>UI user 开关</b>(行为菜单,默认 true)—— 用户主动关闭后回退直连 MCP,
 *       进程被主动停止(见 {@code McpGatewayService.stopGateway})</li>
 *   <li><b>-D 子开关</b> {@code cli.enabled}/{@code sdk.enabled} —— 仅关对应路径,
 *       以总开关为前提</li>
 * </ol>
 *
 * <p>单测无 Application 实例,无法经 {@code CodemossSettingsService.getInstance()} 读取
 * 真实 user 开关;通过 {@link McpGatewayFeatureFlags#testUserEnabledOverride} 包级钩子注入。
 */
public class McpGatewayFeatureFlagsTest {
    @Before
    public void clearFlags() {
        System.clearProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_CLI_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_SDK_ENABLED);
        // null = 走 getInstance() 真实路径(单测环境下 catch 兜底 true)
        McpGatewayFeatureFlags.testUserEnabledOverride = null;
    }

    @After
    public void tearDown() {
        System.clearProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_CLI_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_SDK_ENABLED);
        McpGatewayFeatureFlags.testUserEnabledOverride = null;
    }

    // ── -D 系统属性(运维层)──

    @Test
    public void enabledByDefault() {
        assertTrue(McpGatewayFeatureFlags.isGatewayEnabled());
        assertTrue(McpGatewayFeatureFlags.isCliEnabled());
        assertTrue(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void gatewayFlagOffDisablesEverything() {
        System.setProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "false");
        assertFalse(McpGatewayFeatureFlags.isGatewayEnabled());
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void cliFlagOffDisablesOnlyCli() {
        System.setProperty(McpGatewayConstants.FEATURE_CLI_ENABLED, "false");
        assertTrue(McpGatewayFeatureFlags.isGatewayEnabled());
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertTrue(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void sdkFlagOffDisablesOnlySdk() {
        System.setProperty(McpGatewayConstants.FEATURE_SDK_ENABLED, "false");
        assertTrue(McpGatewayFeatureFlags.isGatewayEnabled());
        assertTrue(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    // ── UI user 开关(行为菜单,第二层门禁)──

    @Test
    public void userToggleOffDisablesBothCliAndSdk() {
        McpGatewayFeatureFlags.testUserEnabledOverride = false;
        assertFalse(McpGatewayFeatureFlags.isUserEnabled());
        // isGatewayEnabled 只读 -D 总开关,不受 user 开关影响
        assertTrue(McpGatewayFeatureFlags.isGatewayEnabled());
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void userToggleOnKeepsEverythingEnabled() {
        McpGatewayFeatureFlags.testUserEnabledOverride = true;
        assertTrue(McpGatewayFeatureFlags.isUserEnabled());
        assertTrue(McpGatewayFeatureFlags.isCliEnabled());
        assertTrue(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void dashFlagOffOverridesUserToggleOn() {
        // -D 总开关是最高优先级运维覆盖:即便 user=true 也整体关
        McpGatewayFeatureFlags.testUserEnabledOverride = true;
        System.setProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "false");
        assertTrue(McpGatewayFeatureFlags.isUserEnabled());
        assertFalse(McpGatewayFeatureFlags.isGatewayEnabled());
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void userToggleOffMakesSubFlagsMoot() {
        // user=false 时,-D 子开关无意义(CLI/SDK 整体已关)
        McpGatewayFeatureFlags.testUserEnabledOverride = false;
        System.setProperty(McpGatewayConstants.FEATURE_CLI_ENABLED, "false");
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    // ── gateway 活跃判定(isGatewayActive = cli||sdk,供预热/重载 gate,不分 CLI/SDK 路径)──

    @Test
    public void gatewayActiveWhenBothCliAndSdkEnabled() {
        assertTrue(McpGatewayFeatureFlags.isGatewayActive());
    }

    @Test
    public void gatewayActiveWhenCliDisabledButSdkEnabled() {
        System.setProperty(McpGatewayConstants.FEATURE_CLI_ENABLED, "false");
        assertFalse(McpGatewayFeatureFlags.isCliEnabled());
        assertTrue(McpGatewayFeatureFlags.isSdkEnabled());
        assertTrue(McpGatewayFeatureFlags.isGatewayActive());
    }

    @Test
    public void gatewayActiveWhenSdkDisabledButCliEnabled() {
        System.setProperty(McpGatewayConstants.FEATURE_SDK_ENABLED, "false");
        assertTrue(McpGatewayFeatureFlags.isCliEnabled());
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
        assertTrue(McpGatewayFeatureFlags.isGatewayActive());
    }

    @Test
    public void gatewayInactiveWhenBothCliAndSdkDisabled() {
        System.setProperty(McpGatewayConstants.FEATURE_CLI_ENABLED, "false");
        System.setProperty(McpGatewayConstants.FEATURE_SDK_ENABLED, "false");
        assertFalse(McpGatewayFeatureFlags.isGatewayActive());
    }

    @Test
    public void gatewayInactiveWhenMasterFlagOff() {
        System.setProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "false");
        assertFalse(McpGatewayFeatureFlags.isGatewayActive());
    }

    @Test
    public void gatewayInactiveWhenUserToggleOff() {
        McpGatewayFeatureFlags.testUserEnabledOverride = false;
        assertFalse(McpGatewayFeatureFlags.isGatewayActive());
    }
}
