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
        // model 取 actualModel(provider/model 形式,如 openglm/glm-5.2):OpenCodeSDKBridge.sendMessage
        // 契约要求 provider/model,裸名(如 glm-5.2)会触发 "Unexpected server error"。与 CLI 模式
        // (OpenCodeCliSession.buildRunCommand 同用 actualModel)对称;actualModel 为 null 时 bridge 传空串,
        // opencode 回退默认模型(优于裸名报错)。
        return bridge.sendMessage(
                req.key().channelId(),
                req.message(),
                req.sessionId(),
                req.cwd(),
                req.actualModel(),
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
