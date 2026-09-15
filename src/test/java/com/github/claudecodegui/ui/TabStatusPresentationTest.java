package com.github.claudecodegui.ui;

import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import javax.swing.Icon;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TabStatusPresentationTest {
    private static final String ZH = "zh";
    private static final String EN = "en";

    @Test
    public void displayNameKeepsTabNameOnlyForAllStatuses() {
        // 状态改为胶囊徽标呈现,标题不再追加纯文本后缀
        for (ChatWindowDelegate.TabAnswerStatus status : ChatWindowDelegate.TabAnswerStatus.values()) {
            assertEquals("修复问题", TabStatusPresentation.displayName("修复问题", status, ZH));
        }
    }

    @Test
    public void fromValueMapsAwaitingApproval() {
        assertEquals(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL,
                ChatWindowDelegate.TabAnswerStatus.fromValue("awaiting_approval"));
        assertEquals(ChatWindowDelegate.TabAnswerStatus.PROCESSING,
                ChatWindowDelegate.TabAnswerStatus.fromValue("answering"));
        assertEquals(ChatWindowDelegate.TabAnswerStatus.IDLE,
                ChatWindowDelegate.TabAnswerStatus.fromValue("unknown"));
        assertEquals(ChatWindowDelegate.TabAnswerStatus.IDLE,
                ChatWindowDelegate.TabAnswerStatus.fromValue(null));
    }

    @Test
    public void statusTextFollowsPluginLanguageNotIdeLocale() {
        // 显式语言参数,与 IDE UI 语言解耦:zh 走中文包,en 走英文基础包
        assertEquals("排队中", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.QUEUED, ZH));
        assertEquals("运行中", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.PROCESSING, ZH));
        assertEquals("已完成", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.COMPLETED, ZH));
        assertEquals("等待确认", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, ZH));

        assertEquals("Queued", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.QUEUED, EN));
        assertEquals("Running", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.PROCESSING, EN));
        assertEquals("Completed", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.COMPLETED, EN));
        assertEquals("Awaiting approval", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, EN));

        // 无语言包的插件语言(de)回落英文包;ko 有语言包,直接命中文案
        assertEquals("Awaiting approval", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, "de"));
        assertEquals("확인 대기 중", TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, "ko"));
    }

    @Test
    public void statusTextCoversActiveStatusesAndHidesIdle() {
        assertNotNull(TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.QUEUED, ZH));
        assertNotNull(TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.PROCESSING, ZH));
        assertNotNull(TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.COMPLETED, ZH));
        assertNotNull(TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, ZH));
        assertNull(TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.IDLE, ZH));
        assertNull(TabStatusPresentation.statusText(null, ZH));
    }

    @Test
    public void stripStatusTextRemovesKnownStatusSuffixesOnly() {
        // 当前语言文案后缀 + 旧版硬编码中文后缀(升级兼容)
        for (String suffix : new String[]{"排队中", "运行中", "已完成",
                TabStatusPresentation.statusText(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL, ZH)}) {
            assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题  " + suffix, ZH));
        }
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题  Completed", EN));
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题", ZH));
    }

    @Test
    public void statusColorsUseApprovedSoftNativePalette() {
        assertEquals(new Color(0xE1B56F), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.QUEUED));
        assertEquals(new Color(0x8FBFFF), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.PROCESSING));
        assertEquals(new Color(0x94D9A8), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.COMPLETED));
        assertEquals(new Color(0x7EE2A0), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL));
        assertNull(TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.IDLE));
        assertNull(TabStatusPresentation.statusColor(null));
    }

    @Test
    public void idleIconIsProviderOnlyAtNativeTabSize() {
        Icon icon = TabStatusPresentation.createProviderIcon(
                CommonConstants.PROVIDER_CODEX,
                ChatWindowDelegate.TabAnswerStatus.IDLE, ZH
        );

        assertNotNull(icon);
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
    }

    @Test
    public void badgeStatusIconExtendsBeyondProviderIconWidth() {
        for (ChatWindowDelegate.TabAnswerStatus status : new ChatWindowDelegate.TabAnswerStatus[]{
                ChatWindowDelegate.TabAnswerStatus.QUEUED,
                ChatWindowDelegate.TabAnswerStatus.PROCESSING,
                ChatWindowDelegate.TabAnswerStatus.COMPLETED,
                ChatWindowDelegate.TabAnswerStatus.AWAITING_APPROVAL}) {
            Icon icon = TabStatusPresentation.createProviderIcon(
                    CommonConstants.PROVIDER_CLAUDE, status, ZH);
            assertNotNull(icon);
            assertEquals(16, icon.getIconHeight());
            assertTrue("badge icon should be wider than provider icon for " + status,
                    icon.getIconWidth() > 16);
        }
    }
}
