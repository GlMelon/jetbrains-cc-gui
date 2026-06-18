package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * 单一入口路由器（取代 sendClaude/sendCodex + if/else）。
 * <p>
 * 按 (ProviderType, RuntimeType) 路由到 4 个 SessionRuntime 实现类之一。
 * CLI 模式路由到 CliSessionManager（零 SDK 依赖）。
 * SDK 模式路由到 provider SDK bridge（ai-bridge daemon）。
 */
public class SessionRuntimeRouter {
    private static final Logger LOG = Logger.getInstance(SessionRuntimeRouter.class);

    private final SessionRuntimeRegistry registry;
    // CLI 子进程聚合器(进程面板可见性 #12):提升为字段,使 router 能对外收集 CLI 子进程。
    private final CliSessionManager cliManager;

    public SessionRuntimeRouter(ClaudeSDKBridge claudeSDKBridge, CodexSDKBridge codexSDKBridge) {
        this.registry = new SessionRuntimeRegistry();
        this.cliManager = new CliSessionManager();
        // 注册 4 个 runtime 实现
        registry.register(new ClaudeSdkSessionRuntime(claudeSDKBridge));
        registry.register(new CodexSdkSessionRuntime(codexSDKBridge));
        registry.register(new ClaudeCliSessionRuntime(cliManager));
        registry.register(new CodexCliSessionRuntime(cliManager));
    }

    /**
     * 统一入口：按 (provider, runtimeType) 路由到对应 runtime 实现。
     */
    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback cb) {
        return registry.resolve(req.provider(), req.runtimeType()).send(req, cb);
    }

    /**
     * 统一中断：按 (provider, runtimeType) 路由到对应 runtime 实现。
     */
    public void interrupt(ProviderType provider, RuntimeType runtimeType, String tabId) {
        registry.resolve(provider, runtimeType).interrupt(tabId);
    }

    /**
     * 释放 tab 资源：遍历所有 runtime 实现执行 disposeTab。
     */
    public void disposeTab(String tabId) {
        registry.all().forEach(r -> r.disposeTab(tabId));
    }

    /**
     * 收集指定 tab 的 CLI 子进程（委托 CliSessionManager），供进程面板可见。
     * SDK runtime 无 CLI 子进程，此方法仅 CLI 模式生效。
     */
    public void collectCliProcesses(String tabId, java.util.function.BiConsumer<String, Process> sink) {
        cliManager.collectActiveProcesses(tabId, sink);
    }
}
