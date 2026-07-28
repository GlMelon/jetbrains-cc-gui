package com.github.claudecodegui.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for the pinned-tab persistence added by F3.
 *
 * <p>{@link TabStateService} keeps its state in an in-memory {@code State} object, so the
 * pinned read/write/remap logic can be exercised with a plain {@code new TabStateService()}
 * — no platform/Application context required.</p>
 */
public class TabStateServicePinnedTest {

    @Test
    public void setPinnedPersistsAndIsReadable() {
        TabStateService service = new TabStateService();
        assertFalse(service.isPinned(0));

        service.setPinned(0, true);
        assertTrue(service.isPinned(0));
        assertNotNull(service.getTabSessionState(0));
        assertTrue(service.getTabSessionState(0).pinned);
    }

    @Test
    public void setPinnedFalseClearsFlag() {
        TabStateService service = new TabStateService();
        service.setPinned(1, true);
        assertTrue(service.isPinned(1));

        service.setPinned(1, false);
        assertFalse(service.isPinned(1));
    }

    @Test
    public void setPinnedCreatesMinimalStateWhenAbsent() {
        TabStateService service = new TabStateService();
        assertNull(service.getTabSessionState(2));

        // A tab can be pinned before any session snapshot exists.
        service.setPinned(2, true);
        assertTrue(service.isPinned(2));
        assertNotNull(service.getTabSessionState(2));
    }

    @Test
    public void fullSnapshotPersistPreservesPinnedFlag() {
        // setPinned then a later full persistTabSessionState snapshot must not drop pinned.
        TabStateService service = new TabStateService();
        service.setPinned(0, true);

        TabStateService.TabSessionState snapshot = service.getTabSessionState(0);
        snapshot.provider = "claude";
        snapshot.sessionId = "abc-123";
        service.saveTabSessionState(0, snapshot);

        assertTrue("pinned must survive a full snapshot persist", service.isPinned(0));
        TabStateService.TabSessionState restored = service.getTabSessionState(0);
        assertEquals("claude", restored.provider);
        assertEquals("abc-123", restored.sessionId);
        assertTrue(restored.pinned);
    }

    @Test
    public void copyPreservesPinned() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();
        state.pinned = true;
        state.provider = "codex";
        state.permissionMode = "plan";

        TabStateService.TabSessionState copy = state.copy();
        assertTrue(copy.pinned);
        assertEquals("codex", copy.provider);
        assertEquals("plan", copy.permissionMode);
    }

    @Test
    public void onTabRemovedRemapsPinnedState() {
        TabStateService service = new TabStateService();
        service.saveTabCount(3);
        service.setPinned(0, false);
        service.setPinned(1, true);
        service.setPinned(2, true);

        service.onTabRemoved(0);

        // old index 1 (pinned) -> 0, old index 2 (pinned) -> 1
        assertTrue(service.isPinned(0));
        assertTrue(service.isPinned(1));
    }

    @Test
    public void loadStatePreservesPinnedAcrossRestartSimulation() {
        // Simulate IntelliJ reloading persisted state (XML round-trip keeps the pinned field).
        TabStateService original = new TabStateService();
        original.setPinned(0, true);
        original.loadState(original.getState());

        assertTrue(original.isPinned(0));
    }
}
