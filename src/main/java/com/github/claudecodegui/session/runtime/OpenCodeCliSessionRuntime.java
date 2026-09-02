package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;

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
    public CompletableFuture<CliResult> send(SessionRequest req, MessageCallback callback) {
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
        // 项8:RuntimeKey 紧凑构造器 normalizeRequired 已强制 tabId 非空非 blank(channelId fallback 是死代码,删除)。
        String tabId = req.key().tabId();
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
                req.codexServiceTier(),
                req.permissionSessionId(),
                req.thinkingOutputEnabled(),
                req.env(),
                req.key().runtimeSessionEpoch(),
                req.responseTurnEpoch()
        );
    }
}
