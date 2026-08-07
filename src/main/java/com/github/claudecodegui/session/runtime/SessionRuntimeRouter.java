package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.intellij.openapi.project.Project;

import java.util.concurrent.CompletableFuture;

/**
 * 单一入口路由器（取代 sendClaude/sendCodex + if/else）。
 * <p>
 * 按 (ProviderType, RuntimeType) 路由到 4 个 SessionRuntime 实现类之一。
 * CLI 模式路由到 CliSessionManager（零 SDK 依赖）。
 * SDK 模式路由到 provider SDK bridge（ai-bridge daemon）。
 * <p>
 * <b>装配 vs 路由(E7 决策·接受并标注)</b>:路由主体({@link #send} / {@link #interrupt} /
 * {@link #disposeTab})经 {@link SessionRuntimeRegistry} Map 查表,新增 provider runtime
 * <b>不需改主体</b>(总则五·开闭已满足,验收达成),仅装配构造函数需加一行 {@code register}。
 * 装配层手工 {@code new} + {@code register} 是无 DI 容器的 IntelliJ 插件装配惯例 ——
 * 4 个 SessionRuntime 实现依赖异构(Claude/Codex Sdk 依赖各自 SDKBridge,Cli 依赖
 * {@link CliSessionManager}),无法像 {@link CliSessionManager}(单一 cliManager 依赖,
 * E1 已 {@code CliSessionFactory} 工厂化)那样统一工厂签名,强推工厂注册表是样板 churn。
 * 故评估接受手工装配并标注(E7),非待修复。
 */
public class SessionRuntimeRouter {

    private final SessionRuntimeRegistry registry;
    // CLI 子进程聚合器(进程面板可见性 #12):提升为字段,使 router 能对外收集 CLI 子进程。
    private final CliSessionManager cliManager;

    public SessionRuntimeRouter(ClaudeSDKBridge claudeSDKBridge, CodexSDKBridge codexSDKBridge) {
        this(claudeSDKBridge, codexSDKBridge, null);
    }

    public SessionRuntimeRouter(ClaudeSDKBridge claudeSDKBridge, CodexSDKBridge codexSDKBridge,
                                OpenCodeSDKBridge openCodeSDKBridge) {
        this(null, claudeSDKBridge, codexSDKBridge, openCodeSDKBridge);
    }

    public SessionRuntimeRouter(Project project, ClaudeSDKBridge claudeSDKBridge, CodexSDKBridge codexSDKBridge,
                                OpenCodeSDKBridge openCodeSDKBridge) {
        this.registry = new SessionRuntimeRegistry();
        this.cliManager = project != null ? new CliSessionManager(project) : new CliSessionManager();
        // 注册 9 个 runtime 实现（6 provider × CLI + 3 provider × SDK）
        registry.register(new ClaudeSdkSessionRuntime(claudeSDKBridge));
        registry.register(new CodexSdkSessionRuntime(codexSDKBridge));
        if (openCodeSDKBridge != null) {
            registry.register(new OpenCodeSdkSessionRuntime(openCodeSDKBridge));
        }
        registry.register(new ClaudeCliSessionRuntime(cliManager));
        registry.register(new CodexCliSessionRuntime(cliManager));
        registry.register(new OpenCodeCliSessionRuntime(cliManager));
        registry.register(new GrokCliSessionRuntime(cliManager));
        registry.register(new KimiCliSessionRuntime(cliManager));
        registry.register(new PiCliSessionRuntime(cliManager));
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

}
