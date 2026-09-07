package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.provider.common.MessageCallback;

import java.util.concurrent.CompletableFuture;

/**
 * MiniMax CLI runtime adapter。
 * 将 SessionRequest 转换为 CliSendRequest，委托给 CliSessionManager。
 * 对称 {@link PiCliSessionRuntime}(同为直 spawn 原生 CLI 的 run-once provider)。
 */
public class MiniMaxCliSessionRuntime implements SessionRuntime {

    private final CliSessionManager cliManager;

    public MiniMaxCliSessionRuntime(CliSessionManager cliManager) {
        this.cliManager = cliManager;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.MINIMAX;
    }

    @Override
    public CompletableFuture<CliResult> send(SessionRequest req, MessageCallback callback) {
        CliSendRequest cliReq = toCliSendRequest(req);
        return cliManager.send(cliReq, callback);
    }

    @Override
    public void interrupt(String tabId) {
        cliManager.interrupt(tabId, ProviderType.MINIMAX.value());
    }

    @Override
    public void disposeTab(String tabId) {
        cliManager.disposeTab(tabId);
    }

    static CliSendRequest toCliSendRequest(SessionRequest req) {
        String tabId = req.key().tabId();
        return new CliSendRequest(
                tabId,
                ProviderType.MINIMAX.value(),
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
                req.codexServiceTier(),
                req.permissionSessionId(),
                req.thinkingOutputEnabled(),
                req.env(),
                req.key().runtimeSessionEpoch(),
                req.responseTurnEpoch()
        );
    }
}
