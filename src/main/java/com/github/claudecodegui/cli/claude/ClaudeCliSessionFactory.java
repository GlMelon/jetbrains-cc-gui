package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;

/**
 * Claude CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CliConstants#PROVIDER_CLAUDE},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class ClaudeCliSessionFactory implements CliSessionFactory {

    @Override
    public String provider() {
        return CliConstants.PROVIDER_CLAUDE;
    }

    @Override
    public CliSession create(String tabId) {
        return new ClaudeCliSession(tabId);
    }
}
