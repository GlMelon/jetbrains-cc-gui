package com.github.claudecodegui.cli.claude;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ClaudeCliSession#isResultLine} — the persistent-mode
 * turn-termination predicate (daemon-mode design §4.1): a {@code "type":"result"}
 * line ends the current turn while the process stays resident. False positives
 * would truncate turns early; false negatives would hang the turn.
 */
public class ClaudeCliSessionResultLineTest {

    private static final Gson GSON = new Gson();

    @Test
    public void matchesResultLine() {
        assertTrue(ClaudeCliSession.isResultLine(GSON,
                "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"done\"}"));
    }

    @Test
    public void matchesResultLineWithErrorSubtype() {
        // 被中断轮以 error_during_execution 收尾(§4.3 V1 实测),仍须判定为轮结束
        assertTrue(ClaudeCliSession.isResultLine(GSON,
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\"}"));
    }

    @Test
    public void rejectsAssistantAndSystemLines() {
        assertFalse(ClaudeCliSession.isResultLine(GSON,
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"}}"));
        assertFalse(ClaudeCliSession.isResultLine(GSON,
                "{\"type\":\"system\",\"subtype\":\"init\"}"));
    }

    @Test
    public void rejectsNonResultType() {
        assertFalse(ClaudeCliSession.isResultLine(GSON, "{\"type\":\"user\"}"));
    }

    @Test
    public void rejectsMalformedAndDegenerateInput() {
        assertFalse(ClaudeCliSession.isResultLine(GSON, "not json at all"));
        assertFalse(ClaudeCliSession.isResultLine(GSON, ""));
        assertFalse(ClaudeCliSession.isResultLine(GSON, (String) null));
        assertFalse(ClaudeCliSession.isResultLine(GSON, "{\"message\":\"no type field\"}"));
    }
}
