package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约单测:typed handler 必须绑定正确的上行 action + payload 类型。
 *
 * <p>handle() 的异步行为(读 PropertiesComponent → invokeLater → dispatchEvent)与旧
 * ClaudeCliPathHandler.handleGetClaudeCliPath 逐字等价(见实现注释),其等价性靠源码
 * 对照 + SettingsHandlerTypedWiringTest 的 wiring 守门保证,不在此单测内(纯 JUnit 无
 * IntelliJ Application 环境,无法驱动 ApplicationManager.invokeLater / PropertiesComponent)。
 *
 * <p>本项目 testImplementation 仅声明 JUnit 4(build.gradle),沿用同目录既有 JUnit 4 风格。
 */
public class GetClaudeCliPathActionHandlerTest {

    @Test
    public void bindsGetClaudeCliPathUpstreamActionWithRawStringPayload() {
        GetClaudeCliPathActionHandler handler = new GetClaudeCliPathActionHandler();

        assertEquals(UpstreamAction.GET_CLAUDE_CLI_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
