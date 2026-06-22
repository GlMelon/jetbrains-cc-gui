package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Typed handler for {@code save_markdown} action.
 *
 * @see com.github.claudecodegui.handler.file.FileExportHandler 旧实现（待删除）
 */
public final class SaveMarkdownActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(SaveMarkdownActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SAVE_MARKDOWN;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        LOG.info("[SaveMarkdownActionHandler] 处理: save_markdown");
        FileExportUtils.handleSaveFile(
                context.handlerContext(),
                payload,
                ".md",
                com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("file.saveMarkdownDialog"));
    }
}
