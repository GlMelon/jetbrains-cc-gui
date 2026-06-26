package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;

/**
 * OpenCode CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_OPENCODE},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class OpenCodeCliSessionFactory implements CliSessionFactory {

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_OPENCODE;
    }

    @Override
    public CliSession create(String tabId) {
        return new OpenCodeCliSession(tabId);
    }
}
