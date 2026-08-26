package com.github.claudecodegui;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LegacyToolWindowCompatibilityTest {

    @Test
    public void legacyToolWindowClassRemainsAssignableToCurrentImplementation() {
        assertTrue(
            com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow.class
                .isAssignableFrom(ClaudeSDKToolWindow.class)
        );
    }
}
