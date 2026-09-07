package com.github.claudecodegui.action.tab;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Action to show a searchable popup listing all opened chat tabs of the AI Code GUI tool window.
 * The current tab is highlighted, each closeable tab offers an inline close button,
 * and typing in the search field filters the list (case-insensitive).
 */
public class ShowOpenedTabsAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(ShowOpenedTabsAction.class);

    /** Width of the right-edge hot zone that triggers the per-item close button. */
    private static final int CLOSE_HOT_ZONE_WIDTH = 24;
    private static final int POPUP_MIN_WIDTH = 300;
    private static final int POPUP_MIN_HEIGHT = 260;

    public ShowOpenedTabsAction() {
        super(
            ClaudeCodeGuiBundle.message("action.showOpenedTabs.text"),
            ClaudeCodeGuiBundle.message("action.showOpenedTabs.description"),
            null
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = project != null && !project.isDisposed()
                && ToolWindowManager.getInstance(project).getToolWindow(ClaudeChatToolWindow.TOOL_WINDOW_ID) != null;
        e.getPresentation().setEnabled(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.warn("[ShowOpenedTabsAction] Project is null");
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(ClaudeChatToolWindow.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            LOG.warn("[ShowOpenedTabsAction] Tool window not found");
            return;
        }

        ContentManager contentManager = toolWindow.getContentManager();

        SearchTextField searchField = new SearchTextField();
        JTextField searchEditor = searchField.getTextEditor();
        if (searchEditor instanceof JBTextField) {
            ((JBTextField) searchEditor).getEmptyText()
                    .setText(ClaudeCodeGuiBundle.message("action.showOpenedTabs.searchEmptyText"));
        }

        CollectionListModel<Content> listModel = new CollectionListModel<>(new ArrayList<>());
        JBList<Content> list = new JBList<>(listModel);
        list.setCellRenderer(new TabListCellRenderer(contentManager));

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(searchField, BorderLayout.NORTH);
        JScrollPane scrollPane = new JBScrollPane(list);
        panel.add(scrollPane, BorderLayout.CENTER);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, searchField)
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(false)
                .setMovable(false)
                .setMinSize(new Dimension(JBUI.scale(POPUP_MIN_WIDTH), JBUI.scale(POPUP_MIN_HEIGHT)))
                .setTitle(ClaudeCodeGuiBundle.message("action.showOpenedTabs.popupTitle"))
                .createPopup();

        Runnable refreshList = () -> {
            applyFilter(contentManager, listModel, list, searchField.getText());
            if (contentManager.getContentCount() == 0 || listModel.getSize() == 0) {
                popup.cancel();
            }
        };

        Runnable activateSelected = () -> {
            Content selected = list.getSelectedValue();
            if (selected == null && listModel.getSize() > 0) {
                selected = listModel.getElementAt(0);
            }
            if (selected == null) {
                return;
            }
            popup.closeOk(null);
            contentManager.setSelectedContent(selected, true);
            toolWindow.activate(null);
        };

        Runnable closeSelected = () -> {
            Content selected = list.getSelectedValue();
            if (selected != null) {
                closeContent(contentManager, selected, refreshList);
            }
        };

        // List keyboard bindings (only when the list itself is focused).
        list.registerKeyboardAction(ev -> activateSelected.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
        list.registerKeyboardAction(ev -> closeSelected.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_FOCUSED);
        list.registerKeyboardAction(ev -> closeSelected.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), JComponent.WHEN_FOCUSED);

        // Search field keyboard bindings: Enter activates, Up/Down move the list selection.
        searchEditor.registerKeyboardAction(ev -> activateSelected.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
        searchEditor.registerKeyboardAction(ev -> moveSelection(listModel, list, 1),
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);
        searchEditor.registerKeyboardAction(ev -> moveSelection(listModel, list, -1),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), JComponent.WHEN_FOCUSED);

        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                applyFilter(contentManager, listModel, list, searchField.getText());
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int index = list.locationToIndex(event.getPoint());
                if (index < 0) {
                    return;
                }
                Content content = listModel.getElementAt(index);
                Rectangle cellBounds = list.getCellBounds(index, index);
                boolean inCloseZone = cellBounds != null
                        && event.getX() >= cellBounds.x + cellBounds.width - JBUI.scale(CLOSE_HOT_ZONE_WIDTH);
                if (inCloseZone && isCloseable(contentManager, content)) {
                    closeContent(contentManager, content, refreshList);
                    return;
                }
                list.setSelectedIndex(index);
                activateSelected.run();
            }
        });

        // Initial content: full tab list, current tab selected.
        applyFilter(contentManager, listModel, list, "");
        Content selectedContent = contentManager.getSelectedContent();
        if (selectedContent != null) {
            int selectedIndex = listModel.getElementIndex(selectedContent);
            if (selectedIndex >= 0) {
                list.setSelectedIndex(selectedIndex);
                list.ensureIndexIsVisible(selectedIndex);
            }
        }

        Component contextComponent = e.getInputEvent() != null ? e.getInputEvent().getComponent() : null;
        if (contextComponent instanceof JComponent && contextComponent.isShowing()) {
            popup.showUnderneathOf((JComponent) contextComponent);
        } else {
            popup.showInFocusCenter();
        }
    }

    /**
     * Rebuilds the list model from the current contents, keeping only tabs whose
     * display name contains the filter text (case-insensitive).
     */
    private static void applyFilter(ContentManager contentManager,
                                    CollectionListModel<Content> listModel,
                                    JBList<Content> list,
                                    String filter) {
        String normalizedFilter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Content> matched = new ArrayList<>();
        for (Content content : contentManager.getContents()) {
            String displayName = content.getDisplayName();
            if (normalizedFilter.isEmpty()
                    || (displayName != null && displayName.toLowerCase(Locale.ROOT).contains(normalizedFilter))) {
                matched.add(content);
            }
        }

        Content previousSelection = list.getSelectedValue();
        listModel.removeAll();
        listModel.add(matched);
        if (matched.contains(previousSelection)) {
            list.setSelectedValue(previousSelection, true);
        } else if (!matched.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private static void moveSelection(CollectionListModel<Content> listModel, JBList<Content> list, int delta) {
        int size = listModel.getSize();
        if (size == 0) {
            return;
        }
        int index = list.getSelectedIndex() + delta;
        index = Math.max(0, Math.min(index, size - 1));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    /**
     * A tab is closeable only when there is more than one tab AND it is not pinned,
     * mirroring ClaudeChatToolWindow.updateTabCloseableState.
     */
    private static boolean isCloseable(ContentManager contentManager, Content content) {
        return contentManager.getContentCount() > 1 && !ClaudeChatToolWindow.isPinned(content);
    }

    /**
     * Closes a tab via ContentManager.removeContent on the EDT: this is the only correct
     * close path (triggers the close confirmation, tab state migration and window disposal).
     */
    private static void closeContent(ContentManager contentManager, Content content, Runnable refreshList) {
        if (!isCloseable(contentManager, content)) {
            return;
        }
        contentManager.removeContent(content, true);
        refreshList.run();
    }

    /**
     * Renderer showing tab icon + display name, a marker for the currently selected tab,
     * a pin marker for pinned tabs and an inline close icon for closeable tabs.
     */
    private static final class TabListCellRenderer extends JPanel implements ListCellRenderer<Content> {

        private final ContentManager contentManager;
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel pinLabel = new JLabel(AllIcons.General.Pin);
        private final JLabel closeLabel = new JLabel(AllIcons.Actions.Close);

        private TabListCellRenderer(ContentManager contentManager) {
            super(new BorderLayout(JBUI.scale(6), 0));
            this.contentManager = contentManager;
            setBorder(JBUI.Borders.empty(4, 8));

            JPanel eastPanel = new JPanel();
            eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
            eastPanel.setOpaque(false);
            pinLabel.setBorder(JBUI.Borders.emptyRight(4));
            eastPanel.add(pinLabel);
            eastPanel.add(Box.createHorizontalStrut(JBUI.scale(2)));
            eastPanel.add(closeLabel);

            add(iconLabel, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
            add(eastPanel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Content> list,
                                                      Content content,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            iconLabel.setIcon(content.getIcon());

            boolean isCurrent = content == contentManager.getSelectedContent();
            String displayName = content.getDisplayName() == null ? "" : content.getDisplayName();
            nameLabel.setText(isCurrent ? "● " + displayName : displayName);
            Font baseFont = list.getFont();
            nameLabel.setFont(isCurrent ? baseFont.deriveFont(Font.BOLD) : baseFont.deriveFont(Font.PLAIN));

            pinLabel.setVisible(ClaudeChatToolWindow.isPinned(content));

            boolean closeable = isCloseable(contentManager, content);
            closeLabel.setVisible(closeable);
            closeLabel.setToolTipText(closeable
                    ? ClaudeCodeGuiBundle.message("action.showOpenedTabs.closeTooltip") : null);

            setBackground(isSelected
                    ? UIUtil.getListSelectionBackground(cellHasFocus)
                    : UIUtil.getListBackground());
            nameLabel.setForeground(isSelected
                    ? UIUtil.getListSelectionForeground(cellHasFocus)
                    : UIUtil.getListForeground());
            setOpaque(true);
            return this;
        }
    }
}
