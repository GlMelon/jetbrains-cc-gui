package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;

import java.util.concurrent.CompletableFuture;

/**
 * DSH(DeepSeek Harness) CLI runtime adapter。
 * 将 SessionRequest 转换为 CliSendRequest,委托给 CliSessionManager(后者路由到 DshCliSessionFactory→ChannelCliSession,
 * 经 ai-bridge channel-manager.js dsh send 触发 host RPC 流程)。对称 {@link OmpCliSessionRuntime}/{@link PiCliSessionRuntime}。
 */
public class DshCliSessionRuntime implements SessionRuntime {

    private final CliSessionManager cliManager;

    public DshCliSessionRuntime(CliSessionManager cliManager) {
        this.cliManager = cliManager;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.DSH;
    }

    @Override
    public CompletableFuture<CliResult> send(SessionRequest req, MessageCallback callback) {
        CliSendRequest cliReq = toCliSendRequest(req);
        return cliManager.send(cliReq, callback);
    }

    @Override
    public void interrupt(String tabId) {
        cliManager.interrupt(tabId, ProviderType.DSH.value());
    }

    @Override
    public void disposeTab(String tabId) {
        cliManager.disposeTab(tabId);
    }

    static CliSendRequest toCliSendRequest(SessionRequest req) {
        String tabId = req.key().tabId();
        return new CliSendRequest(
                tabId,
                ProviderType.DSH.value(),
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
