package com.github.claudecodegui.notifications;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.*;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimalist StatusBar widget for Claude AI
 */
public class ClaudeStatusBarWidget implements CustomStatusBarWidget, StatusBarWidget, Disposable {
    private static final int LOGO_ICON_SIZE = 16;
    private static final Icon LOGO_ICON = createScaledLogoIcon();

    private final Project project;
    private StatusBar statusBar;
    private JLabel label;
    private final AtomicReference<String> textRef = new AtomicReference<>("");
    private final AtomicReference<String> tooltipRef = new AtomicReference<>(ClaudeCodeGuiBundle.message("status.defaultTooltip"));
    private final AtomicLong visibleUntil = new AtomicLong(0);

    // Thread-safe state for display
    private final AtomicReference<String> currentStatus = new AtomicReference<>(CommonConstants.SESSION_STATUS_READY);
    private final AtomicReference<String> currentTokenInfo = new AtomicReference<>("");
    private final AtomicReference<String> currentModel = new AtomicReference<>("");
    private final AtomicReference<String> currentMode = new AtomicReference<>(CommonConstants.PERMISSION_MODE_DEFAULT);
    private final AtomicReference<String> currentAgent = new AtomicReference<>("");

    // Auto-hide scheduler; parent=this lets the platform release the Alarm
    // on the Disposer tree when the widget is disposed.
    private final Alarm hideAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

    // Track disposed state to prevent operations after disposal
    private volatile boolean disposed = false;

    public ClaudeStatusBarWidget(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return "ClaudeStatusBarWidget";
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return new WidgetPresentation() {
            @Nullable
            @Override
            public String getTooltipText() {
                return tooltipRef.get();
            }

            @Nullable
            @Override
            public com.intellij.util.Consumer<MouseEvent> getClickConsumer() {
                return null;
            }
        };
    }

    @Override
    public @NotNull JComponent getComponent() {
        if (label == null) {
            label = new JLabel(textRef.get(), LOGO_ICON, SwingConstants.LEFT);
            label.setIconTextGap(4);
            label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            label.setToolTipText(tooltipRef.get());
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (project.isDisposed()) { return; }
                    var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ClaudeChatToolWindow.TOOL_WINDOW_ID);
                    if (toolWindow != null) {
                        toolWindow.activate(null);
                    }
                }
            });
        }
        return label;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public void dispose() {
        disposed = true;
        // Cancel any pending hide; the Alarm itself is released by the platform
        // (parent=this on the Disposer tree) when the widget is disposed.
        hideAlarm.cancelAllRequests();
        this.statusBar = null;
    }

    public void updateStatus(String status, String details) {
        this.currentStatus.set(status);
        refreshDisplay(details);
    }

    public void setTokenInfo(String tokenInfo) {
        this.currentTokenInfo.set(tokenInfo);
        refreshDisplay(null);
    }

    public void setModel(String model) {
        this.currentModel.set(model);
        refreshDisplay(null);
    }

    public void setMode(String mode) {
        this.currentMode.set(mode);
        refreshDisplay(null);
    }

    public void setAgent(String agent) {
        this.currentAgent.set(agent);
        refreshDisplay(null);
    }

    private void refreshDisplay(String details) {
        StatusBarPresentation.Presentation presentation = StatusBarPresentation.present(
                new StatusBarPresentation.State(
                        currentStatus.get(),
                        currentModel.get(),
                        currentMode.get(),
                        currentAgent.get(),
                        currentTokenInfo.get(),
                        details
                )
        );
        updateLabel(presentation.text(), presentation.tooltip());
    }

    public void show(String text, String tooltip, long durationMs) {
        if (disposed) { return; }
        // Cancel any pending hide request so a new show restarts the schedule
        hideAlarm.cancelAllRequests();
        this.visibleUntil.set(System.currentTimeMillis() + durationMs);
        // Temporary override
        updateLabel(text, tooltip);
        // Alarm requests are non-repeating by default, equivalent to setRepeats(false)
        hideAlarm.addRequest(this::hide, (int) durationMs);
    }

    public void hide() {
        if (disposed) { return; }
        if (System.currentTimeMillis() >= visibleUntil.get()) {
            // Revert to standard display
            refreshDisplay(null);
        }
    }

    private void updateLabel(String text, String tooltip) {
        if (disposed) { return; }
        textRef.set(text);
        tooltipRef.set(tooltip);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            updateLabelOnEdt(text, tooltip);
        } else {
            ApplicationManager.getApplication().invokeLater(() -> updateLabelOnEdt(text, tooltip));
        }
    }

    private void updateLabelOnEdt(String text, String tooltip) {
        if (disposed) { return; }
        if (label != null) {
            label.setText(text);
            label.setIcon(LOGO_ICON);
            label.setToolTipText(tooltip);
        }
        if (statusBar != null) { statusBar.updateWidget(ID()); }
    }

    private static Icon createScaledLogoIcon() {
        Icon raw = IconLoader.getIcon("/icons/logo.svg", ClaudeStatusBarWidget.class);
        return new Icon() {
            @Override
            public int getIconWidth() { return LOGO_ICON_SIZE; }

            @Override
            public int getIconHeight() { return LOGO_ICON_SIZE; }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    int w = raw.getIconWidth();
                    int h = raw.getIconHeight();
                    double scale = Math.min((double) LOGO_ICON_SIZE / w, (double) LOGO_ICON_SIZE / h);
                    int sw = (int) Math.round(w * scale);
                    int sh = (int) Math.round(h * scale);
                    int dx = x + (LOGO_ICON_SIZE - sw) / 2;
                    int dy = y + (LOGO_ICON_SIZE - sh) / 2;
                    g2.translate(dx, dy);
                    g2.scale(scale, scale);
                    raw.paintIcon(c, g2, 0, 0);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    public static class Factory implements StatusBarWidgetFactory {
        @Override
        public @NotNull String getId() {
            return "ClaudeStatusBarWidget";
        }

        @Override
        public @NotNull String getDisplayName() {
            return ClaudeCodeGuiBundle.message("status.widgetName");
        }

        @Override
        public boolean isAvailable(@NotNull Project project) {
            if (project == null) { return false; }
            try {
                return CodemossSettingsService.getInstance().getStatusBarWidgetEnabled();
            } catch (Exception e) {
                return true;
            }
        }

        @Override
        public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
            return new ClaudeStatusBarWidget(project);
        }

        // disposeWidget is intentionally NOT overridden: the platform default runs
        // Disposer.dispose(widget), which cascades to the hideAlarm registered with
        // parent=this and then invokes dispose() (whose cancelAllRequests is a
        // harmless second safety net).

        @Nullable
        public static ClaudeStatusBarWidget getWidget(@NotNull Project project) {
            StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
            if (statusBar != null) {
                StatusBarWidget widget = statusBar.getWidget("ClaudeStatusBarWidget");
                if (widget instanceof ClaudeStatusBarWidget) { return (ClaudeStatusBarWidget) widget; }
            }
            return null;
        }
    }
}
