package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Structural guards for the F3 pinned-tab wiring across the ToolWindow persistence layer.
 *
 * <p>These cover the platform-bound code paths (ContentManager closeable decisions, per-Content
 * {@code PINNED_KEY}, snapshot capture/restore) that are not reachable from a plain JUnit test
 * without an Application/ToolWindow fixture. The behavioral persistence logic is covered by
 * {@code TabStateServicePinnedTest}.</p>
 */
public class TabPersistenceContractTest {

    private static final Path SDK_TW = Path.of(
            "src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatToolWindow.java");
    private static final Path CHAT_WINDOW = Path.of(
            "src/main/java/com/github/claudecodegui/ui/toolwindow/ClaudeChatWindow.java");
    private static final Path TAB_STATE = Path.of(
            "src/main/java/com/github/claudecodegui/settings/TabStateService.java");
    private static final Path PIN_ACTION = Path.of(
            "src/main/java/com/github/claudecodegui/action/tab/PinTabAction.java");

    @Test
    public void closeableDecisionRespectsPinnedFlag() throws Exception {
        String source = read(SDK_TW);
        assertTrue("updateTabCloseableState must consult isPinned",
                source.contains("tabCount > 1 && !isPinned(tab)"));
    }

    @Test
    public void restoreAppliesPinnedKeyToContent() throws Exception {
        String source = read(SDK_TW);
        assertTrue("restoreTabSessionState must mirror persisted pinned into PINNED_KEY",
                source.contains("savedState.pinned") && source.contains("PINNED_KEY"));
        assertTrue("PINNED_KEY + isPinned helper must exist",
                source.contains("PINNED_KEY =") && source.contains("static boolean isPinned(Content"));
    }

    @Test
    public void persistCapturesPinnedFromParentContent() throws Exception {
        String source = read(CHAT_WINDOW);
        assertTrue("persistTabSessionState must capture pinned from parentContent",
                source.contains("snapshot.pinned = parentContent != null && ClaudeChatToolWindow.isPinned(parentContent)"));
    }

    @Test
    public void restoreLogsDegradationForMissingProvider() throws Exception {
        String source = read(CHAT_WINDOW);
        assertTrue("restorePersistedTabSessionState must warn when provider binding is missing",
                source.contains("Tab restored without a provider binding"));
    }

    @Test
    public void tabStateServiceExposesPinnedApi() throws Exception {
        String source = read(TAB_STATE);
        assertTrue(source.contains("public void setPinned(int tabIndex, boolean pinned)"));
        assertTrue(source.contains("public boolean isPinned(int tabIndex)"));
        assertTrue(source.contains("public boolean pinned;"));
    }

    @Test
    public void pinTabActionIsToggleAndPersists() throws Exception {
        String source = read(PIN_ACTION);
        assertTrue("PinTabAction must be a toggle action for menu selected-state",
                source.contains("extends ToggleAction"));
        assertTrue("must mirror pinned into PINNED_KEY",
                source.contains("ClaudeChatToolWindow.PINNED_KEY"));
        assertTrue("must persist via TabStateService.setPinned",
                source.contains("setPinned(tabIndex, state)"));
        assertTrue("must update closeable after toggle",
                source.contains("setCloseable("));
        assertFalse("must not crash when content manager is absent (no NPE-prone chaining)",
                source.contains(".getSelectedContent().get"));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
