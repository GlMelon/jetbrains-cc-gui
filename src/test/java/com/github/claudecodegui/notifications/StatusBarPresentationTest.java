package com.github.claudecodegui.notifications;

import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StatusBarPresentationTest {
    @Test
    public void presentUsesResolvedModelAsIsWithoutHardcodedShortening() {
        StatusBarPresentation.Presentation presentation = StatusBarPresentation.present(
                new StatusBarPresentation.State(
                        CommonConstants.SESSION_STATUS_READY,
                        "anthropic/claude-sonnet-4.5",
                        CommonConstants.PERMISSION_MODE_DEFAULT,
                        "",
                        "",
                        ""
                )
        );

        assertTrue(presentation.text().contains("anthropic/claude-sonnet-4.5"));
        assertFalse(presentation.text().contains("Sonnet 4.6"));
    }

    @Test
    public void labelsLocalizeRawStatusAndModeValues() {
        assertNotEquals(CommonConstants.SESSION_STATUS_READY,
                StatusBarPresentation.statusLabel(CommonConstants.SESSION_STATUS_READY));
        assertNotEquals(CommonConstants.PERMISSION_MODE_DEFAULT,
                StatusBarPresentation.modeLabel(CommonConstants.PERMISSION_MODE_DEFAULT));
        assertNotEquals(CommonConstants.PERMISSION_MODE_AUTO_EDIT,
                StatusBarPresentation.modeLabel(CommonConstants.PERMISSION_MODE_AUTO_EDIT));
    }
}
