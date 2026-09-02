package com.github.claudecodegui.cli;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CliSendRequestTest {

    @Test
    public void preservesPermissionSessionIdForCliPermissionRouting() {
        CliSendRequest request = new CliSendRequest(
                "tab-1",
                "claude",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "acceptEdits",
                "claude-sonnet-4-6",
                null,
                "high",
                "permission-session-123",
                Map.of()
        );

        assertEquals("permission-session-123", request.permissionSessionId());
    }

    @Test
    public void acceptsMissingPermissionSessionIdForNonPermissionCliRequests() {
        CliSendRequest request = new CliSendRequest(
                "tab-1",
                "codex",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                "gpt-5.3-codex",
                null,
                "high",
                null,
                Map.of()
        );

        assertNull(request.permissionSessionId());
    }

    @Test
    public void defaultsThinkingOutputEnabledForBackwardCompatibleRequests() {
        CliSendRequest request = new CliSendRequest(
                "tab-1",
                "codex",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                "gpt-5.3-codex",
                null,
                "high",
                null,
                Map.of()
        );

        assertTrue(request.thinkingOutputEnabled());
    }

    @Test
    public void preservesExplicitThinkingOutputDisabled() {
        CliSendRequest request = new CliSendRequest(
                "tab-1",
                "codex",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                "gpt-5.3-codex",
                null,
                "high",
                null,
                Boolean.FALSE,
                Map.of()
        );

        assertFalse(request.thinkingOutputEnabled());
    }

    @Test
    public void preservesCodexServiceTierAndNormalizesBlankValues() {
        CliSendRequest fastRequest = new CliSendRequest(
                "tab-1",
                "codex",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                "gpt-5.3-codex",
                null,
                "high",
                "fast",
                null,
                Boolean.TRUE,
                Map.of(),
                "epoch-1",
                1L
        );
        CliSendRequest blankRequest = new CliSendRequest(
                "tab-1",
                "codex",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                "gpt-5.3-codex",
                null,
                "high",
                "   ",
                null,
                Boolean.TRUE,
                Map.of(),
                null,
                0L
        );

        assertEquals("fast", fastRequest.codexServiceTier());
        assertNull(blankRequest.codexServiceTier());
    }
}
