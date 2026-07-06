package com.github.claudecodegui.ui;

import com.github.claudecodegui.session.runtime.ProviderType;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Native-looking tab presentation for chat provider/status.
 */
final class TabStatusPresentation {
    private static final int ICON_SIZE = 16;
    private static final int ARC = 6;
    private static final String STATUS_SEPARATOR = "  ";

    static final Color QUEUED_COLOR = new Color(0xE1B56F);
    static final Color PROCESSING_COLOR = new Color(0x8FBFFF);
    static final Color COMPLETED_COLOR = new Color(0x94D9A8);

    private static final Color ICON_FILL = new Color(0x3B3D40);
    private static final Color ICON_IDLE_BORDER = new Color(0x6F737A);
    private static final Color ICON_TEXT = new Color(0xD7DAE0);

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
        return new ProviderStatusIcon(ProviderType.fromString(provider), statusColor(status));
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

    private static String providerMark(ProviderType providerType) {
        return switch (providerType) {
            case CLAUDE -> "C";
            case CODEX -> "X";
            case OPENCODE -> "O";
        };
    }

    private static final class ProviderStatusIcon implements Icon {
        private final ProviderType providerType;
        private final Color statusColor;

        private ProviderStatusIcon(ProviderType providerType, Color statusColor) {
            this.providerType = providerType;
            this.statusColor = statusColor;
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

                int left = x + 1;
                int top = y + 1;
                int size = ICON_SIZE - 3;
                g2.setColor(ICON_FILL);
                g2.fillRoundRect(left, top, size, size, ARC, ARC);

                g2.setStroke(new BasicStroke(statusColor == null ? 1.0f : 1.4f));
                g2.setColor(statusColor == null ? ICON_IDLE_BORDER : statusColor);
                g2.drawRoundRect(left, top, size, size, ARC, ARC);

                String mark = providerMark(providerType);
                Font baseFont = UIManager.getFont("Label.font");
                if (baseFont == null) {
                    baseFont = new Font(Font.DIALOG, Font.PLAIN, 10);
                }
                Font markFont = baseFont.deriveFont(Font.BOLD, 9.0f);
                g2.setFont(markFont);
                FontMetrics metrics = g2.getFontMetrics();
                int textX = x + (ICON_SIZE - metrics.stringWidth(mark)) / 2;
                int textY = y + ((ICON_SIZE - metrics.getHeight()) / 2) + metrics.getAscent() - 1;
                g2.setColor(ICON_TEXT);
                g2.drawString(mark, textX, textY);
            } finally {
                g2.dispose();
            }
        }
    }
}
