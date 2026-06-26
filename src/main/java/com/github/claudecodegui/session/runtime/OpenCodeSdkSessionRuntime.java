package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.util.concurrent.CompletableFuture;

/**
 * OpenCode SDK runtime adapter。
 * 将 SessionRequest 转发给 OpenCodeSDKBridge.sendMessage()。
 * <p>
 * OpenCode SDK 模式通过 Node.js bridge 层（channel-manager.js → @opencode-ai/sdk）
 * 与 OpenCode HTTP API（opencode serve）交互。
 */
public class OpenCodeSdkSessionRuntime implements SessionRuntime {

    private final OpenCodeSDKBridge bridge;

    public OpenCodeSdkSessionRuntime(OpenCodeSDKBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
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
                req.cwd(),
                req.model(),
                callback
        );
    }

    @Override
    public void interrupt(String tabId) {
        // SDK 模式中断由 bridge 内部处理
    }
}
