package com.github.claudecodegui.cli;

import java.util.concurrent.CompletableFuture;

/**
 * CLI 会话多态接口。
 * <p>
 * ClaudeCliSession / CodexCliSession 各实现此接口，
 * CliSessionManager 面向接口容器，消除 provider 专用 Map。
 * <p>
 * 方法签名以两个 CLI session 现有公共方法为准对齐，
 * 确保仅加 implements、不改内部逻辑。
 */
public interface CliSession {

    /**
     * 发送消息到 CLI 进程。
     *
     * @param request  CLI 发送请求
     * @param callback CLI 会话回调
     * @return 异步结果（Void）
     */
    CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback);

    /**
     * 中断当前 CLI 进程。
     */
    void interrupt();

    /**
     * 释放 CLI 会话资源（中断进程 + 清理临时文件等）。
     */
    void dispose();

    /**
     * 返回当前活跃的 CLI 子进程（若存在且存活），供 NodeProcessRegistry 进程面板注册可见。
     * 默认返回 null（SDK runtime 无 CLI 子进程）。CLI 实现类按需 override。
     */
    default Process activeProcess() {
        return null;
    }
}
