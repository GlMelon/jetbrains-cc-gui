package com.github.claudecodegui.ui;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Native-looking tab presentation for chat provider/status.
 */
final class TabStatusPresentation {
    private static final int ICON_SIZE = 16;
    private static final String STATUS_SEPARATOR = "  ";

    private static final Icon CLAUDE_ICON = IconLoader.getIcon(
            "/icons/providers/claude.svg", TabStatusPresentation.class);
    private static final Icon CODEX_ICON = IconLoader.getIcon(
            "/icons/providers/codex.svg", TabStatusPresentation.class);
    private static final Icon OPENCODE_ICON = IconLoader.getIcon(
            "/icons/providers/opencode.svg", TabStatusPresentation.class);
    private static final Icon GROK_ICON = IconLoader.getIcon(
            "/icons/providers/grok.svg", TabStatusPresentation.class);
    private static final Icon KIMI_ICON = IconLoader.getIcon(
            "/icons/providers/kimi.svg", TabStatusPresentation.class);
    private static final Icon PI_ICON = IconLoader.getIcon(
            "/icons/providers/pi.svg", TabStatusPresentation.class);

    static final Color QUEUED_COLOR = new Color(0xE1B56F);
    static final Color PROCESSING_COLOR = new Color(0x8FBFFF);
    static final Color COMPLETED_COLOR = new Color(0x94D9A8);

    private TabStatusPresentation() {
    }

    static String displayName(String tabName, ChatWindowDelegate.TabAnswerStatus status) {
        String normalizedTabName = tabName == null ? "" : stripStatusText(tabName);
        String statusText = statusText(status);
        if (statusText == null) {
            return normalizedTabName;
        }
        return normalizedTabName + STATUS_SEPARATOR + statusText;
    }

    static String stripStatusText(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return displayName;
        }
        for (ChatWindowDelegate.TabAnswerStatus status : ChatWindowDelegate.TabAnswerStatus.values()) {
            String statusText = statusText(status);
            if (statusText == null) {
                continue;
            }
            String suffix = STATUS_SEPARATOR + statusText;
            if (displayName.endsWith(suffix)) {
                return displayName.substring(0, displayName.length() - suffix.length());
            }
        }
        return displayName;
    }

    static Icon createProviderIcon(String provider, ChatWindowDelegate.TabAnswerStatus status) {
        return new ProviderStatusIcon(providerIcon(ProviderType.fromString(provider)));
    }

    static String statusText(ChatWindowDelegate.TabAnswerStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case QUEUED -> "排队中";
            case PROCESSING -> "运行中";
            case COMPLETED -> "已完成";
            case IDLE -> null;
        };
    }

    static Color statusColor(ChatWindowDelegate.TabAnswerStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case QUEUED -> QUEUED_COLOR;
            case PROCESSING -> PROCESSING_COLOR;
            case COMPLETED -> COMPLETED_COLOR;
            case IDLE -> null;
        };
    }

    private static Icon providerIcon(ProviderType providerType) {
        return switch (providerType) {
            case CLAUDE -> CLAUDE_ICON;
            case CODEX -> CODEX_ICON;
            case OPENCODE -> OPENCODE_ICON;
            case GROK -> GROK_ICON;
            case KIMI -> KIMI_ICON;
            case PI -> PI_ICON;
            // omp/dsh 暂复用 codex/opencode 图标占位(批次 D 补真实图标)
            case OMP -> CODEX_ICON;
            case DSH -> OPENCODE_ICON;
        };
    }

    private static final class ProviderStatusIcon implements Icon {
        private final Icon providerIcon;

        private ProviderStatusIcon(Icon providerIcon) {
            this.providerIcon = providerIcon;
        }

        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int iconX = x + Math.max(0, (ICON_SIZE - providerIcon.getIconWidth()) / 2);
                int iconY = y + Math.max(0, (ICON_SIZE - providerIcon.getIconHeight()) / 2);
                providerIcon.paintIcon(component, g2, iconX, iconY);
            } finally {
                g2.dispose();
            }
        }
    }
}
