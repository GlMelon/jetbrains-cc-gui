package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;

/**
 * Codex CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_CODEX},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class CodexCliSessionFactory implements CliSessionFactory {

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_CODEX;
    }

    @Override
    public CliSession create(String tabId) {
        return new CodexCliSession(tabId);
    }
}
