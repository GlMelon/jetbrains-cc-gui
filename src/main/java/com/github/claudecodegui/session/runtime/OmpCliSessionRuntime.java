package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * OMP CLI runtime adapter。
 * 将 SessionRequest 转换为 CliSendRequest,委托给 CliSessionManager(后者路由到 OmpCliSessionFactory→ChannelCliSession)。
 * 对称 {@link PiCliSessionRuntime}。
 */
public class OmpCliSessionRuntime implements SessionRuntime {

    private final CliSessionManager cliManager;

    public OmpCliSessionRuntime(CliSessionManager cliManager) {
        this.cliManager = cliManager;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OMP;
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
        cliManager.interrupt(tabId, ProviderType.OMP.value());
    }

    @Override
    public void disposeTab(String tabId) {
        cliManager.disposeTab(tabId);
    }

    static CliSendRequest toCliSendRequest(SessionRequest req) {
        String tabId = req.key().tabId();
        return new CliSendRequest(
                tabId,
                ProviderType.OMP.value(),
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
