package com.github.claudecodegui.action.tab;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Toggles the pinned (close-protected) state of the currently selected tab.
 *
 * <p>A pinned tab cannot be closed via the ContentManager close button, and the state is
 * persisted in {@link TabStateService} so it survives IDE restart.</p>
 */
public class PinTabAction extends ToggleAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(PinTabAction.class);

    public PinTabAction() {
        super(
            ClaudeCodeGuiBundle.message("action.pinTab.text"),
            ClaudeCodeGuiBundle.message("action.pinTab.description"),
            null
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        Content selected = selectedContent(e.getProject());
        return selected != null && ClaudeChatToolWindow.isPinned(selected);
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        Project project = e.getProject();
        ContentManager contentManager = contentManager(project);
        if (contentManager == null) {
            LOG.warn("[PinTabAction] Content manager unavailable");
            return;
        }
        Content selected = contentManager.getSelectedContent();
        if (selected == null) {
            LOG.warn("[PinTabAction] No tab selected");
            return;
        }
        int tabIndex = contentManager.getIndexOfContent(selected);
        // Mirror the persisted state into the per-Content runtime flag so the closeable
        // decision and the next persistTabSessionState snapshot stay consistent.
        selected.putUserData(ClaudeChatToolWindow.PINNED_KEY, state);
        if (tabIndex >= 0) {
            TabStateService.getInstance(project).setPinned(tabIndex, state);
        }
        // A pinned tab is never closeable; an unpinned tab follows the "keep at least one" rule.
        selected.setCloseable(contentManager.getContentCount() > 1 && !state);
        LOG.info("[PinTabAction] Tab " + tabIndex + " pinned=" + state);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        e.getPresentation().setEnabledAndVisible(selectedContent(e.getProject()) != null);
    }

    @Nullable
    private static Content selectedContent(@Nullable Project project) {
        ContentManager contentManager = contentManager(project);
        return contentManager == null ? null : contentManager.getSelectedContent();
    }

    @Nullable
    private static ContentManager contentManager(@Nullable Project project) {
        if (project == null) {
            return null;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(ClaudeChatToolWindow.TOOL_WINDOW_ID);
        return toolWindow == null ? null : toolWindow.getContentManagerIfCreated();
    }
}
