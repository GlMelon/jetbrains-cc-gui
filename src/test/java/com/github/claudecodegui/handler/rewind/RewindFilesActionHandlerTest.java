package com.github.claudecodegui.handler.rewind;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the typed rewind handler and Claude UUID validation.
 */
public class RewindFilesActionHandlerTest {

    @Test
    public void bindsRewindFilesUpstreamActionWithStringPayload() {
        RewindFilesActionHandler handler = new RewindFilesActionHandler();
        assertEquals(UpstreamAction.REWIND_FILES, handler.action());
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void acceptsStrictClaudeUuidsOnly() {
        assertTrue(RewindFilesActionHandler.isStrictUuid(
                "11111111-1111-4111-8111-111111111111"
        ));
        assertFalse(RewindFilesActionHandler.isStrictUuid("ses_not-a-claude-uuid"));
        assertFalse(RewindFilesActionHandler.isStrictUuid(null));
    }
}
