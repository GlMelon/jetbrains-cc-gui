package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;

import java.util.concurrent.CompletableFuture;

/**
 * 统一 runtime 契约接口（work 模块风格）。
 * <p>
 * 每个实现类对应一个 provider（SDK 调用模式已移除，runtime 维度已消除，
 * 全部 provider 统一走 CLI 子进程），负责：
 * <ul>
 *   <li>声明自己的路由键（provider）</li>
 *   <li>将 SessionRequest 转发给底层 CLI session</li>
 *   <li>处理 interrupt / disposeTab 生命周期</li>
 * </ul>
 */
public interface SessionRuntime {

    /**
     * 返回此 runtime 对应的 provider 类型。
     */
    ProviderType provider();

    /**
     * 发送消息到对应的 AI 提供者。
     *
     * @param req      统一请求
     * @param callback 消息回调
     * @return 异步结果
     */
    CompletableFuture<CliResult> send(SessionRequest req, MessageCallback callback);

    /**
     * 中断指定 tab 的当前操作。
     *
     * @param tabId 标签页 ID
     */
    void interrupt(String tabId);

    /**
     * 释放指定 tab 的资源。默认无操作。
     *
     * @param tabId 标签页 ID
     */
    default void disposeTab(String tabId) {}
}
