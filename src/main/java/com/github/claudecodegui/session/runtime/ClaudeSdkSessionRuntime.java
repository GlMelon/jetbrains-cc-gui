package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * Claude SDK runtime adapter。
 * 将 SessionRequest 转发给 ClaudeSDKBridge.sendMessage()。
 * 参数保持与原 SDK 发送路径一致。
 */
public class ClaudeSdkSessionRuntime implements SessionRuntime {

    private final ClaudeSDKBridge bridge;

    public ClaudeSdkSessionRuntime(ClaudeSDKBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CLAUDE;
    }

    @Override
    public RuntimeType runtimeType() {
        return RuntimeType.SDK;
    }

    @Override
    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback callback) {
        return bridge.sendMessage(
                req.key().channelId(),
                req.message(),
                req.sessionId(),
                req.key().runtimeSessionEpoch(),
                req.cwd(),
                req.attachments(),
                req.permissionMode(),
                req.model(),
                req.actualModel(),
                req.openedFiles(),
                req.agentPrompt(),
                req.streaming(),
                false,
                req.reasoningEffort(),
                CommonConstants.INVOCATION_MODE_SDK,
                callback
        );
    }

    @Override
    public void interrupt(String tabId) {
        // SDK 模式中断由 bridge 内部处理
    }
}
