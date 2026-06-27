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
        // §15.7 B11:透传 permissionMode/reasoningEffort/attachments,与 Claude/Codex 对齐。
        // baseUrl 由 OpenCodeSDKBridge 内部经 DaemonCoordinator 解析注入。
        return bridge.sendMessage(
                req.key().channelId(),
                req.message(),
                req.sessionId(),
                req.cwd(),
                req.model(),
                req.permissionMode(),
                req.reasoningEffort(),
                req.attachments(),
                callback
        );
    }

    @Override
    public void interrupt(String tabId) {
        // SDK 模式中断由 bridge 内部处理
    }
}
