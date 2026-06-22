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
import java.awt.datatransfer.StringSelection;

/**
 * OCP typed handler:取代旧 {@code ClipboardHandler} 对 {@code write_clipboard} 的字符串派发
 * (AGENTS.md §2 开闭原则)。
 *
 * <p>逐字搬移 {@code ClipboardHandler.handleWriteClipboard}:写入大小上限校验(10MB,防滥用)
 * + EDT 剪贴板写入({@code ModalityState.any()}),与旧实现逐字等价。
 *
 * <p>payload 为原始剪贴板文本(非 JSON),{@code payloadType=String} 直传。
 * Security:size bound 保护,防止 WebView JS 写入超大内容。
 */
public final class WriteClipboardActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(WriteClipboardActionHandler.class);
    private static final int MAX_CLIPBOARD_WRITE_SIZE = 10 * 1024 * 1024; // 10 MB

    @Override
    public UpstreamAction action() {
        return UpstreamAction.WRITE_CLIPBOARD;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        if (payload != null && payload.length() > MAX_CLIPBOARD_WRITE_SIZE) {
            LOG.warn("Clipboard write rejected: content too large (" + payload.length() + " chars)");
            return;
        }
        final String content = payload;
        // Dispatch clipboard access to EDT to avoid blocking the CEF browser thread.
        // Use ModalityState.any() so copy works even when a modal dialog (e.g. PermissionDialog) is open.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(content), null);
            } catch (Exception e) {
                LOG.warn("Failed to write clipboard", e);
            }
        }, ModalityState.any());
    }
}
