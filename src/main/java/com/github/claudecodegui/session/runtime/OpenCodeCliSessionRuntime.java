package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * OpenCode CLI runtime adapter。
 * 将 SessionRequest 转换为 CliSendRequest，委托给 CliSessionManager。
 */
public class OpenCodeCliSessionRuntime implements SessionRuntime {

    private final CliSessionManager cliManager;

    public OpenCodeCliSessionRuntime(CliSessionManager cliManager) {
        this.cliManager = cliManager;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
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
        cliManager.interrupt(tabId, ProviderType.OPENCODE.value());
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
                ProviderType.OPENCODE.value(),
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
                req.env()
        );
    }
}
