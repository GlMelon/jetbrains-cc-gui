package com.github.claudecodegui.ui;

import com.github.claudecodegui.i18n.WebviewLocaleBundle;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Native-looking tab presentation for chat provider/status.
 *
 * <p>状态展示为「自绘胶囊徽标」:标签标题只保留会话名,状态文案与颜色由
 * {@link StatusPillIcon} 绘制在 provider 图标右侧(IDE 标签标题是单色
 * JLabel 文本,无法对后缀文本分段着色,故颜色提示走自绘 Icon 管道)。</p>
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

    /** 规范调色板(深色主题取值,浅色主题由 {@link #themeForegroundColor} 映射)。 */
    static final Color QUEUED_COLOR = new Color(0xE1B56F);
    static final Color PROCESSING_COLOR = new Color(0x8FBFFF);
    static final Color COMPLETED_COLOR = new Color(0x94D9A8);
    static final Color AWAITING_COLOR = new Color(0x7EE2A0);

    /** 徽标底色透明度(状态色 16% 左右,贴合 New UI badge 观感)。 */
    private static final int BADGE_BG_ALPHA = 0x2B;
    private static final int BADGE_GAP = 4;
    private static final int BADGE_HEIGHT = 16;
    private static final int BADGE_CORNER_RADIUS = 8;
    private static final int BADGE_H_PADDING = 6;
    private static final int BADGE_DOT_SIZE = 4;
    private static final int BADGE_DOT_GAP = 3;

    private TabStatusPresentation() {
    }

    static String displayName(String tabName, ChatWindowDelegate.TabAnswerStatus status, String language) {
        return tabName == null ? "" : stripStatusText(tabName, language);
    }

    /**
     * 剥离历史上以纯文本后缀(「标签名 + 两空格 + 状态文案」)持久化的标签名,
     * 覆盖指定语言文案与旧版硬编码中文文案(升级场景:语言为非中文但标签名带旧后缀)。
     */
    static String stripStatusText(String displayName, String language) {
        if (displayName == null || displayName.isEmpty()) {
            return displayName;
        }
        for (String statusText : collectKnownStatusTexts(language)) {
            String suffix = STATUS_SEPARATOR + statusText;
            if (displayName.endsWith(suffix)) {
                return displayName.substring(0, displayName.length() - suffix.length());
            }
        }
        return displayName;
    }

    static Icon createProviderIcon(String provider, ChatWindowDelegate.TabAnswerStatus status, String language) {
        Icon baseProviderIcon = providerIcon(ProviderType.fromString(provider));
        String text = statusText(status, language);
        if (text == null) {
            return new ProviderStatusIcon(baseProviderIcon);
        }
        return new StatusPillIcon(baseProviderIcon, status, text);
    }

    /** 状态文案按插件语言从 webview 语言包 JSON 读取(用户手动语言 &gt; IDEA 语言),不跟随 IDE UI 语言。 */
    static String statusText(ChatWindowDelegate.TabAnswerStatus status, String language) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case QUEUED -> WebviewLocaleBundle.text(language, "tabStatus", "queued");
            case PROCESSING -> WebviewLocaleBundle.text(language, "tabStatus", "processing");
            case COMPLETED -> WebviewLocaleBundle.text(language, "tabStatus", "completed");
            case AWAITING_APPROVAL -> WebviewLocaleBundle.text(language, "tabStatus", "awaitingApproval");
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
            case AWAITING_APPROVAL -> AWAITING_COLOR;
            case IDLE -> null;
        };
    }

    private static Color themeForegroundColor(ChatWindowDelegate.TabAnswerStatus status) {
        boolean bright = JBColor.isBright();
        return switch (status) {
            case QUEUED -> bright ? new Color(0x8F5F0F) : QUEUED_COLOR;
            case PROCESSING -> bright ? new Color(0x2158D2) : PROCESSING_COLOR;
            case COMPLETED -> bright ? new Color(0x1F7A34) : COMPLETED_COLOR;
            case AWAITING_APPROVAL -> bright ? new Color(0x0D8A3D) : AWAITING_COLOR;
            case IDLE -> null;
        };
    }

    private static Set<String> collectKnownStatusTexts(String language) {
        Set<String> texts = new LinkedHashSet<>();
        for (ChatWindowDelegate.TabAnswerStatus status : ChatWindowDelegate.TabAnswerStatus.values()) {
            String text = statusText(status, language);
            if (text != null) {
                texts.add(text);
            }
        }
        // 旧版硬编码中文后缀(升级兼容:当前语言非中文但持久化标签名带旧后缀)
        texts.add("排队中");
        texts.add("运行中");
        texts.add("已完成");
        return texts;
    }

    private static Icon providerIcon(ProviderType providerType) {
        return switch (providerType) {
            case CLAUDE -> CLAUDE_ICON;
            case CODEX -> CODEX_ICON;
            case OPENCODE -> OPENCODE_ICON;
            case GROK -> GROK_ICON;
            case KIMI -> KIMI_ICON;
            case PI -> PI_ICON;
            // omp/dsh/minimax 暂复用 codex/opencode 图标占位(批次 D 补真实图标)
            case OMP -> CODEX_ICON;
            case DSH -> OPENCODE_ICON;
            case MINIMAX -> CODEX_ICON;
        };
    }

    private static boolean statusHasDot(ChatWindowDelegate.TabAnswerStatus status) {
        return status == ChatWindowDelegate.TabAnswerStatus.QUEUED
                || status == ChatWindowDelegate.TabAnswerStatus.PROCESSING
                || status == ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL;
    }

    /** 单测/无组件环境下计算文本宽度的共享 FontMetrics。 */
    private static FontMetrics badgeFontMetrics(Font font) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        return image.getGraphics().getFontMetrics(font);
    }

    /**
     * provider 图标 + 状态胶囊徽标的复合标签图标。
     * 徽标:圆角矩形底(状态色 @ {@link #BADGE_BG_ALPHA}) + 呼吸点(非终态) + 状态文案。
     * 文案在构造时按插件语言解析完成,绘制期零 I/O。
     */
    private static final class StatusPillIcon implements Icon {
        private final Icon providerIcon;
        private final ChatWindowDelegate.TabAnswerStatus status;
        private final String badgeText;
        private final Font badgeFont = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10.5f);

        private StatusPillIcon(Icon providerIcon, ChatWindowDelegate.TabAnswerStatus status, String badgeText) {
            this.providerIcon = providerIcon;
            this.status = status;
            this.badgeText = badgeText;
        }

        private int badgeWidth() {
            FontMetrics metrics = badgeFontMetrics(badgeFont);
            int width = 2 * BADGE_H_PADDING + metrics.stringWidth(badgeText);
            if (statusHasDot(status)) {
                width += BADGE_DOT_SIZE + BADGE_DOT_GAP;
            }
            return Math.max(width, BADGE_HEIGHT);
        }

        @Override
        public int getIconWidth() {
            return ICON_SIZE + BADGE_GAP + badgeWidth();
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

                Color foreground = themeForegroundColor(status);
                if (foreground == null) {
                    return;
                }
                int badgeWidth = badgeWidth();
                int badgeX = x + ICON_SIZE + BADGE_GAP;

                g2.setColor(new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), BADGE_BG_ALPHA));
                g2.fillRoundRect(badgeX, y, badgeWidth, BADGE_HEIGHT, BADGE_CORNER_RADIUS, BADGE_CORNER_RADIUS);

                g2.setFont(badgeFont);
                FontMetrics metrics = g2.getFontMetrics();
                int textX = badgeX + BADGE_H_PADDING;
                if (statusHasDot(status)) {
                    g2.setColor(foreground);
                    g2.fillOval(badgeX + BADGE_H_PADDING,
                            y + (BADGE_HEIGHT - BADGE_DOT_SIZE) / 2, BADGE_DOT_SIZE, BADGE_DOT_SIZE);
                    textX += BADGE_DOT_SIZE + BADGE_DOT_GAP;
                }
                int textY = y + (BADGE_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.setColor(foreground);
                g2.drawString(badgeText, textX, textY);
            } finally {
                g2.dispose();
            }
        }
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
