package com.github.claudecodegui.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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

    // 回归:双条目自定义模型(如 registry 中 glm-5.3=200k 与 glm-5.3[1m]=1M 并存)。
    // state.model 存的是剥掉容量后缀的 base id,按 base id 重查 registry 只会命中 200k 条目
    // (find() 内部同样剥后缀,带 [1m] 的条目查不到);resolver 权威写入的 1M override
    // 不得被这次重复 cap 压回 200k(模型列表显示 1M 而 usage 弹窗显示 200k 的根因)。
    // 默认 registry 的 claude-role-sonnet(200k) 即可复现「base id 命中已知低窗口条目」。
    @Test
    public void knownModelOverrideIsNotCappedBackToRegistryBaseEntry() {
        SessionState state = new SessionState();
        state.setModel("claude-role-sonnet");
        state.setContextWindowOverride(1_000_000);

        assertEquals(1_000_000, state.getEffectiveMaxTokens());
    }

    // ── Retired Claude model id migration (persisted tab / history restore self-heal, #1678) ──

    @Test
    public void setModelMigratesRetiredSonnet47ToSonnet5() {
        SessionState state = new SessionState();
        // Saved by versions <= 0.5.2 where sonnet-4-7 was the default model.
        state.setModel("claude-sonnet-4-7");
        assertEquals("claude-sonnet-5", state.getModel());
    }

    @Test
    public void setModelMigratesRetiredSonnet46ToSonnet5() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-4-6");
        assertEquals("claude-sonnet-5", state.getModel());
    }

    @Test
    public void setModelMigratesRetiredOpus46ToOpus48() {
        SessionState state = new SessionState();
        state.setModel("claude-opus-4-6");
        assertEquals("claude-opus-4-8", state.getModel());
    }

    @Test
    public void setModelPreserves1MSuffixWhenMigrating() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-4-7[1m]");
        assertEquals("claude-sonnet-5[1m]", state.getModel());
    }

    @Test
    public void setModelLeavesLiveModelsUntouched() {
        SessionState state = new SessionState();
        state.setModel("claude-sonnet-5");
        assertEquals("claude-sonnet-5", state.getModel());
        state.setModel("claude-opus-4-8[1m]");
        assertEquals("claude-opus-4-8[1m]", state.getModel());
    }

    @Test
    public void setModelLeavesNonClaudeAndUnknownIdsUntouched() {
        SessionState state = new SessionState();
        // Non-Claude provider models must pass through unchanged.
        state.setModel("gpt-5.6-sol");
        assertEquals("gpt-5.6-sol", state.getModel());
        state.setModel("qwen3.5-plus");
        assertEquals("qwen3.5-plus", state.getModel());
    }

    @Test
    public void setModelHandlesNullAndBlank() {
        SessionState state = new SessionState();
        state.setModel(null);
        assertNull(state.getModel());
        // Blank input is trimmed like every other normalizeRetiredModelId path.
        state.setModel("  ");
        assertEquals("", state.getModel());
    }

    @Test
    public void defaultModelIsNeverARetiredId() {
        SessionState state = new SessionState();
        // 本地默认模型走 role 体系(claude-role-sonnet),非 upstream 的具体 model id。
        // 保留 upstream #1678 测试意图:初始值绝不能是任一 retired id(会被 normalizeRetiredModelId
        // 迁移的 sonnet-4-6/4-7/opus-4-6),否则新会话默认落在死模型上。
        String model = state.getModel();
        assertNotEquals("claude-sonnet-4-6", model);
        assertNotEquals("claude-sonnet-4-7", model);
        assertNotEquals("claude-opus-4-6", model);
    }
}
