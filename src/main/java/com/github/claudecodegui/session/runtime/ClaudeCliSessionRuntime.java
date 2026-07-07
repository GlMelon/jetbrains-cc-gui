package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * Claude CLI runtime adapter。
 * 将 SessionRequest 转换为 CliSendRequest，委托给 CliSessionManager。
 */
public class ClaudeCliSessionRuntime implements SessionRuntime {

    private final CliSessionManager cliManager;

    public ClaudeCliSessionRuntime(CliSessionManager cliManager) {
        this.cliManager = cliManager;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CLAUDE;
    }

    @Override
    public RuntimeType runtimeType() {
        return RuntimeType.CLI;
    }

    @Override
    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback callback) {
        CliSendRequest cliReq = toCliSendRequest(req);
        return cliManager.send(cliReq, callback);
    }

    @Override
    public void interrupt(String tabId) {
        cliManager.interrupt(tabId, CommonConstants.PROVIDER_CLAUDE);
    }

    @Override
    public void disposeTab(String tabId) {
        cliManager.disposeTab(tabId);
    }

    static CliSendRequest toCliSendRequest(SessionRequest req) {
        String tabId = req.key().tabId();
        if (tabId == null || tabId.isBlank()) {
            tabId = req.key().channelId();
        }
        return new CliSendRequest(
                tabId,
                CommonConstants.PROVIDER_CLAUDE,
                req.message(),
                req.sessionId(),
                req.cwd(),
                req.attachments(),
                req.openedFiles(),
                req.fileTagPaths(),
                req.agentPrompt(),
                req.permissionMode(),
                req.model(),
                req.actualModel(),
                req.reasoningEffort(),
                req.permissionSessionId(),
                req.thinkingOutputEnabled(),
                req.env()
        );
    }
}
