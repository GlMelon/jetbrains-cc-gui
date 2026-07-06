package com.github.claudecodegui.ui;

import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import javax.swing.Icon;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TabStatusPresentationTest {
    @Test
    public void displayNameAppendsWeakChineseStatusTextForActiveStates() {
        assertEquals("修复问题  排队中",
                TabStatusPresentation.displayName("修复问题", ChatWindowDelegate.TabAnswerStatus.QUEUED));
        assertEquals("修复问题  运行中",
                TabStatusPresentation.displayName("修复问题", ChatWindowDelegate.TabAnswerStatus.PROCESSING));
        assertEquals("修复问题  已完成",
                TabStatusPresentation.displayName("修复问题", ChatWindowDelegate.TabAnswerStatus.COMPLETED));
    }

    @Test
    public void displayNameHidesIdleStatusText() {
        assertEquals("修复问题",
                TabStatusPresentation.displayName("修复问题", ChatWindowDelegate.TabAnswerStatus.IDLE));
    }

    @Test
    public void displayNameDoesNotDuplicateExistingStatusSuffix() {
        assertEquals("修复问题  已完成",
                TabStatusPresentation.displayName("修复问题  运行中", ChatWindowDelegate.TabAnswerStatus.COMPLETED));
    }

    @Test
    public void stripStatusTextRemovesKnownStatusSuffixesOnly() {
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题  排队中"));
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题  运行中"));
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题  已完成"));
        assertEquals("修复问题", TabStatusPresentation.stripStatusText("修复问题"));
    }

    @Test
    public void statusColorsUseApprovedSoftNativePalette() {
        assertEquals(new Color(0xE1B56F), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.QUEUED));
        assertEquals(new Color(0x8FBFFF), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.PROCESSING));
        assertEquals(new Color(0x94D9A8), TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.COMPLETED));
        assertNull(TabStatusPresentation.statusColor(ChatWindowDelegate.TabAnswerStatus.IDLE));
    }

    @Test
    public void providerIconIsAlwaysPresentAndNativeTabSized() {
        Icon icon = TabStatusPresentation.createProviderIcon(
                CommonConstants.PROVIDER_CODEX,
                ChatWindowDelegate.TabAnswerStatus.PROCESSING
        );

        assertNotNull(icon);
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
    }
}
