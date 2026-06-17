package com.github.claudecodegui.action.tab;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TabActionInitializationSafetyTest {

    @Test
    public void tabActionsThatReadContentManagerUiStateRunOnEdt() {
        assertEquals(ActionUpdateThread.EDT, new CreateNewTabAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.EDT, new DetachTabAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.EDT, new RenameTabAction().getActionUpdateThread());
    }

    @Test
    public void passiveTabActionsUseNonCreatingContentManagerAccess() throws Exception {
        assertUsesOnlyNonCreatingContentManagerAccess("DetachTabAction.java");
        assertUsesOnlyNonCreatingContentManagerAccess("RenameTabAction.java");
    }

    @Test
    public void renameTabDefensiveEarlyReturnsUseWarnings() throws Exception {
        String source = readTabActionSource("RenameTabAction.java");

        assertTrue(source.contains("LOG.warn(\"[RenameTabAction] Project is null\")"));
        assertTrue(source.contains("LOG.warn(\"[RenameTabAction] Tool window not found\")"));
        assertTrue(source.contains("LOG.warn(\"[RenameTabAction] Content manager not created\")"));
        assertTrue(source.contains("LOG.warn(\"[RenameTabAction] No tab selected\")"));
        assertFalse(source.contains("LOG.error(\"[RenameTabAction] Project is null\")"));
        assertFalse(source.contains("LOG.error(\"[RenameTabAction] Tool window not found\")"));
        assertFalse(source.contains("LOG.error(\"[RenameTabAction] No tab selected\")"));
    }

    private static void assertUsesOnlyNonCreatingContentManagerAccess(String fileName) throws Exception {
        String source = readTabActionSource(fileName);

        assertTrue(source.contains("getContentManagerIfCreated()"));
        assertFalse(source.contains(".getContentManager()"));
    }

    private static String readTabActionSource(String fileName) throws Exception {
        Path path = Path.of("src", "main", "java", "com", "github", "claudecodegui",
                "action", "tab", fileName);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
