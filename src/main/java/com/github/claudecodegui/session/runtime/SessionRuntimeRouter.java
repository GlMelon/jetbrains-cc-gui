package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSessionManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.intellij.openapi.project.Project;

import java.util.concurrent.CompletableFuture;

/**
 * 单一入口路由器（取代 sendClaude/sendCodex + if/else）。
 * <p>
 * 按 ProviderType 路由到 6 个 CLI SessionRuntime 实现之一。
 * CLI 模式路由到 {@link CliSessionManager}（零 daemon 依赖）。
 * <p>
 * <b>装配 vs 路由(E7 决策·接受并标注)</b>:路由主体({@link #send} / {@link #interrupt} /
 * {@link #disposeTab})经 {@link SessionRuntimeRegistry} Map 查表,新增 provider runtime
 * <b>不需改主体</b>(总则五·开闭已满足),仅装配构造函数需加一行 {@code register}。
 * 装配层手工 {@code new} + {@code register} 是无 DI 容器的 IntelliJ 插件装配惯例。
 */
public class SessionRuntimeRouter {

    private final SessionRuntimeRegistry registry;
    // CLI 子进程聚合器(进程面板可见性 #12):提升为字段,使 router 能对外收集 CLI 子进程。
    private final CliSessionManager cliManager;

    public SessionRuntimeRouter(Project project) {
        this.registry = new SessionRuntimeRegistry();
        this.cliManager = project != null ? new CliSessionManager(project) : new CliSessionManager();
        // 注册 6 个 runtime 实现（6 provider × CLI）
        registry.register(new ClaudeCliSessionRuntime(cliManager));
        registry.register(new CodexCliSessionRuntime(cliManager));
        registry.register(new OpenCodeCliSessionRuntime(cliManager));
        registry.register(new GrokCliSessionRuntime(cliManager));
        registry.register(new KimiCliSessionRuntime(cliManager));
        registry.register(new PiCliSessionRuntime(cliManager));
        registry.register(new OmpCliSessionRuntime(cliManager));
        registry.register(new DshCliSessionRuntime(cliManager));
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

    /** Release all CLI sessions owned by this router. */
    public void dispose() {
        cliManager.dispose();
    }

}
