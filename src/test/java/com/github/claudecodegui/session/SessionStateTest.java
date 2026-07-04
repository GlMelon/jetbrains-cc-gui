package com.github.claudecodegui.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionStateTest {

    @Test
    public void invalidatingPendingSendOperationsMarksEarlierEpochsAsStale() {
        SessionState state = new SessionState();

        long initialEpoch = state.capturePendingSendInvalidationEpoch();
        assertTrue(state.isPendingSendOperationCurrent(initialEpoch));

        state.invalidatePendingSendOperations();

        assertFalse(state.isPendingSendOperationCurrent(initialEpoch));
        assertTrue(state.isPendingSendOperationCurrent(state.capturePendingSendInvalidationEpoch()));
    }

    @Test
    public void claudeInvocationModeDefaultsToSdkUntilExplicitlyChanged() {
        SessionState state = new SessionState();

        assertEquals("sdk", state.getClaudeInvocationMode());

        state.setClaudeInvocationMode("cli");

        assertEquals("cli", state.getClaudeInvocationMode());
    }

    @Test
    public void invalidClaudeInvocationModeDoesNotOverwriteExistingSessionMode() {
        SessionState state = new SessionState();
        state.setClaudeInvocationMode("cli");

        state.setClaudeInvocationMode("bad-mode");

        assertEquals("cli", state.getClaudeInvocationMode());
    }

    @Test
    public void providerRejectsUnknownValues() {
        SessionState state = new SessionState();
        state.setProvider("codex");

        state.setProvider("bad-provider");

        assertEquals("codex", state.getProvider());
    }

    @Test
    public void providerAcceptsKnownValues() {
        SessionState state = new SessionState();

        state.setProvider("codex");
        assertEquals("codex", state.getProvider());

        state.setProvider("claude");
        assertEquals("claude", state.getProvider());
    }

    // B5: VALID_PROVIDERS 必须纳入 opencode,否则 setProvider("opencode") 会被校验拒绝(provider 选择无法持久化)。
    @Test
    public void validProvidersIncludesOpenCode() {
        assertTrue(SessionState.VALID_PROVIDERS.contains("opencode"));
    }

    @Test
    public void providerAcceptsOpenCode() {
        SessionState state = new SessionState();

        state.setProvider("opencode");

        assertEquals("opencode", state.getProvider());
    }

    // 跨 provider 切换时 sessionId 必须清空:三 provider 的 session 协议/格式互不兼容
    // (Claude/Codex=UUID, OpenCode=ses_xxx)。若不清,OpenCode 的 ses_xxx 会污染 state,
    // 切回 Claude CLI 时被原样塞进 `claude -p --resume`,触发 "not a UUID" 崩溃且无法自愈。
    @Test
    public void switchingProviderClearsIncompatibleSessionId() {
        SessionState state = new SessionState();
        state.setProvider("opencode");
        state.setSessionId("ses_0e3ae3fd3ffe51s6nGv4Ow33HP");

        state.setProvider("claude");

        assertNull(state.getSessionId());
    }

    // 同 provider 内重复 setProvider(如 SDK↔CLI 调用模式切换)不得清空 sessionId,
    // 否则正常的会话续接(--resume 合法 UUID)会被误断。
    @Test
    public void sameProviderSetKeepsSessionId() {
        SessionState state = new SessionState();
        state.setProvider("claude");
        state.setSessionId("3d1ccc5a-5816-4ccd-b144-df5b774ec7c8");

        state.setProvider("claude");

        assertEquals("3d1ccc5a-5816-4ccd-b144-df5b774ec7c8", state.getSessionId());
    }

    @Test
    public void unknownModelUsesExplicitContextWindowOverride() {
        SessionState state = new SessionState();
        state.setModel("mimo-v2.5-pro");
        state.setContextWindowOverride(1_000_000);

        assertEquals(1_000_000, state.getEffectiveMaxTokens());
    }
}
