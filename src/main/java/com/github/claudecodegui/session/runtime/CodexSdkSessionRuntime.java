package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * Codex SDK runtime adapter。
 * 将 SessionRequest 转发给 CodexSDKBridge.sendMessageWithDaemonPreferred()。
 */
public class CodexSdkSessionRuntime implements SessionRuntime {

    private final CodexSDKBridge bridge;

    public CodexSdkSessionRuntime(CodexSDKBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public RuntimeType runtimeType() {
        return RuntimeType.SDK;
    }

    @Override
    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback callback) {
        return bridge.sendMessageWithDaemonPreferred(
                req.key().channelId(),
                req.message(),
                req.sessionId(),
                req.cwd(),
                req.attachments(),
                req.permissionMode(),
                req.model(),
                req.agentPrompt(),
                req.reasoningEffort(),
                callback
        );
    }

    @Override
    public void interrupt(String tabId) {
        // SDK 模式中断由 bridge 内部处理
    }
}
