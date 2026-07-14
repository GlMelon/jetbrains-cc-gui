package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryActionHandlersTest {

    @Test
    public void schedulesDelayedRefreshOnlyForOpenCode() {
        assertTrue(HistoryActionHandlers.shouldSchedulePostStreamRefresh(ProviderType.OPENCODE.value()));
        assertFalse(HistoryActionHandlers.shouldSchedulePostStreamRefresh(ProviderType.CLAUDE.value()));
        assertFalse(HistoryActionHandlers.shouldSchedulePostStreamRefresh(ProviderType.CODEX.value()));
        assertFalse(HistoryActionHandlers.shouldSchedulePostStreamRefresh(null));
    }
}
