package com.github.claudecodegui.handler.tab;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;

/**
 * OCP typed handler:取代旧 {@code TabHandler} 对 {@code create_new_tab} 的字符串派发
 * (AGENTS.md §2 开闭原则)。
 *
 * <p>逐字搬移 {@code TabHandler.handleCreateNewTab}:在 ToolWindow 的 EDT 上创建新
 * {@code ClaudeChatWindow} 实例(skipRegister=true,不替换主实例),按 tab 索引恢复或生成 tab 名,
 * 挂载 {@code Content} 并选中、显示工具窗,与旧实现逐字等价。
 *
 * <p>payload 忽略(create_new_tab 无请求体),{@code payloadType=String} 仅为满足
 * {@code FrontendActionDispatcher} 契约;实际逻辑不读取 payload。
 */
public final class CreateNewTabActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(CreateNewTabActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CREATE_NEW_TAB;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        Project project = ctx.getProject();

        ToolWindowManager.getInstance(project).invokeLater(() -> {
            try {
                // Get the tool window
                ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(ClaudeSDKToolWindow.TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    LOG.error("[CreateNewTabActionHandler] Tool window not found");
                    ctx.callJavaScript("addErrorMessage", ctx.escapeJs("无法找到 CCG 工具窗口"));
                    return;
                }

                // Create a new chat window instance with skipRegister=true (don't replace the main instance)
                ClaudeChatWindow newChatWindow = new ClaudeChatWindow(project, true);

                // Get tab index before adding content
                ContentManager contentManager = toolWindow.getContentManager();
                int tabIndex = contentManager.getContentCount();

                // Check if there's a saved name for this tab index
                TabStateService tabStateService = TabStateService.getInstance(project);
                String savedName = tabStateService.getTabName(tabIndex);

                // Create a tab name: use saved name or generate new one
                String tabName;
                if (savedName != null && !savedName.isEmpty()) {
                    tabName = savedName;
                    LOG.info("[CreateNewTabActionHandler] Restored tab name from storage: " + tabName);
                } else {
                    tabName = ClaudeSDKToolWindow.getNextTabName(toolWindow);
                }

                // Create and add the new tab content
                ContentFactory contentFactory = ContentFactory.getInstance();
                Content content = contentFactory.createContent(newChatWindow.getContent(), tabName, false);
                content.setCloseable(true);
                newChatWindow.setParentContent(content);
                content.setDisposer(newChatWindow::dispose);

                contentManager.addContent(content);
                contentManager.setSelectedContent(content);

                // Ensure the tool window is visible
                toolWindow.show(null);

                LOG.info("[CreateNewTabActionHandler] Created new tab: " + tabName);
            } catch (Exception e) {
                LOG.error("[CreateNewTabActionHandler] Error creating new tab: " + e.getMessage(), e);
                ctx.callJavaScript("addErrorMessage", ctx.escapeJs("创建新标签页失败: " + e.getMessage()));
            }
        });
    }
}
