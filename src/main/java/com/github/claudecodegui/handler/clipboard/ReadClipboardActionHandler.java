package com.github.claudecodegui.handler.clipboard;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;

/**
 * OCP typed handler:取代旧 {@code ClipboardHandler} 对 {@code read_clipboard} 的字符串派发
 * (AGENTS.md §2 开闭原则)。
 *
 * <p>逐字搬移 {@code ClipboardHandler.handleReadClipboard}:rate limiting(限频防滥用)
 * + EDT 剪贴板读取(避免阻塞 CEF IO 线程, {@code ModalityState.any()} 保证模态对话框开启时仍可复制)
 * + 经 {@code clipboard.read} 事件回传文本,与旧实现逐字等价。
 *
 * <p>Security:rate limiting 防止 WebView JS 滥用剪贴板监听(与旧实现一致的同步前置检查)。
 */
public final class ReadClipboardActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(ReadClipboardActionHandler.class);
    private static final long MIN_READ_INTERVAL_MS = 200;

    // 限频状态:typed handler 在 ChatWindowDelegate 单例注册,跨请求保持(等价旧 ClipboardHandler 实例字段)。
    private volatile long lastReadTime = 0;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.READ_CLIPBOARD;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        // Rate limiting to prevent clipboard-monitoring abuse (checked synchronously before dispatch)
        long now = System.currentTimeMillis();
        if (now - lastReadTime < MIN_READ_INTERVAL_MS) {
            LOG.debug("Clipboard read rate-limited");
            ctx.dispatchEvent("clipboard.read", "");
            return;
        }
        lastReadTime = now;

        // Dispatch clipboard access to EDT to avoid blocking the CEF browser thread.
        // Use ModalityState.any() so copy works even when a modal dialog (e.g. PermissionDialog) is open.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                    ctx.dispatchEvent("clipboard.read", ctx.escapeJs(text != null ? text : ""));
                } else {
                    ctx.dispatchEvent("clipboard.read", "");
                }
            } catch (Exception e) {
                LOG.warn("Failed to read clipboard", e);
                ctx.dispatchEvent("clipboard.read", "");
            }
        }, ModalityState.any());
    }
}
