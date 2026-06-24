package com.github.claudecodegui.protocol;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProtocolEnumCoverageTest {

    @Test
    public void upstreamContainsActionsUsedBySettingsAndFrontend() {
        assertEquals("set_appearance_config", UpstreamAction.SET_APPEARANCE_CONFIG.value());
        assertEquals("get_model_registry", UpstreamAction.GET_MODEL_REGISTRY.value());
        assertEquals("set_model_registry", UpstreamAction.SET_MODEL_REGISTRY.value());
        assertEquals("reset_model_registry", UpstreamAction.RESET_MODEL_REGISTRY.value());
        assertEquals("get_model_registry_schema", UpstreamAction.GET_MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void downstreamContainsEventsUsedBySettingsAndFrontend() {
        assertEquals("appearance.apply", DownstreamEvent.APPEARANCE_APPLY.value());
        assertEquals("model_registry", DownstreamEvent.MODEL_REGISTRY.value());
        assertEquals("model_registry_updated", DownstreamEvent.MODEL_REGISTRY_UPDATED.value());
        assertEquals("model_registry_schema", DownstreamEvent.MODEL_REGISTRY_SCHEMA.value());
    }

    @Test
    public void protocolValuesAreUniqueWithinEachDirection() {
        assertUnique(Arrays.stream(UpstreamAction.values()).map(UpstreamAction::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(DownstreamEvent.values()).map(DownstreamEvent::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(PermissionMode.values()).map(PermissionMode::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(ReasoningEffort.values()).map(ReasoningEffort::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(ProviderType.values()).map(ProviderType::value).collect(Collectors.toList()));
        assertUnique(Arrays.stream(CodexProtectedEnvKey.values()).map(CodexProtectedEnvKey::value).collect(Collectors.toList()));
    }

    @Test
    public void codexProtectedEnvKeyCoversProtocolValues() {
        // A5:CodexProtectedEnvKey SSOT —— Codex 受保护环境变量名 18 个(基础集)。
        // 对齐前端 isProtectedEnvVarKey 校验集与后端 CodexCliCommandUtils/CodexSDKBridge 基础保护集,
        // 消除三份手抄。CodexSDKBridge 额外 17 个运行时安全变量(NODE_OPTIONS/LD_PRELOAD 等)
        // 属 SDK 注入路径独有,不在此枚举(单独保留)。
        assertEquals("CODEX_USE_STDIN", CodexProtectedEnvKey.CODEX_USE_STDIN.value());
        assertEquals("CODEX_MODEL", CodexProtectedEnvKey.CODEX_MODEL.value());
        assertEquals("CODEX_SANDBOX_MODE", CodexProtectedEnvKey.CODEX_SANDBOX_MODE.value());
        assertEquals("CODEX_SANDBOX", CodexProtectedEnvKey.CODEX_SANDBOX.value());
        assertEquals("CODEX_APPROVAL_POLICY", CodexProtectedEnvKey.CODEX_APPROVAL_POLICY.value());
        assertEquals("CODEX_CI", CodexProtectedEnvKey.CODEX_CI.value());
        assertEquals("CODEX_SANDBOX_NETWORK_DISABLED", CodexProtectedEnvKey.CODEX_SANDBOX_NETWORK_DISABLED.value());
        assertEquals("CODEX_HOME", CodexProtectedEnvKey.CODEX_HOME.value());
        assertEquals("CLAUDE_SESSION_ID", CodexProtectedEnvKey.CLAUDE_SESSION_ID.value());
        assertEquals("CLAUDE_PERMISSION_DIR", CodexProtectedEnvKey.CLAUDE_PERMISSION_DIR.value());
        assertEquals("HOME", CodexProtectedEnvKey.HOME.value());
        assertEquals("PATH", CodexProtectedEnvKey.PATH.value());
        assertEquals("TMPDIR", CodexProtectedEnvKey.TMPDIR.value());
        assertEquals("TEMP", CodexProtectedEnvKey.TEMP.value());
        assertEquals("TMP", CodexProtectedEnvKey.TMP.value());
        assertEquals("IDEA_PROJECT_PATH", CodexProtectedEnvKey.IDEA_PROJECT_PATH.value());
        assertEquals("PROJECT_PATH", CodexProtectedEnvKey.PROJECT_PATH.value());
        assertEquals("CLAUDE_USE_STDIN", CodexProtectedEnvKey.CLAUDE_USE_STDIN.value());
        assertEquals(18, CodexProtectedEnvKey.values().length);
    }

    @Test
    public void codexProtectedEnvKeyFromValueRoundTrip() {
        for (CodexProtectedEnvKey key : CodexProtectedEnvKey.values()) {
            assertEquals(Optional.of(key), CodexProtectedEnvKey.fromValue(key.value()));
        }
        assertEquals(Optional.empty(), CodexProtectedEnvKey.fromValue("NONEXISTENT_ENV_KEY"));
    }

    @Test
    public void permissionModeCoversProtocolValues() {
        // C2:PermissionMode SSOT 值域对齐 SessionState.VALID_PERMISSION_MODES(5 值,含 autoEdit 别名)
        assertEquals("default", PermissionMode.DEFAULT.value());
        assertEquals("acceptEdits", PermissionMode.ACCEPT_EDITS.value());
        assertEquals("plan", PermissionMode.PLAN.value());
        assertEquals("bypassPermissions", PermissionMode.BYPASS_PERMISSIONS.value());
        assertEquals("autoEdit", PermissionMode.AUTO_EDIT.value());
    }

    @Test
    public void permissionModeFromValueRoundTrip() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertEquals(Optional.of(mode), PermissionMode.fromValue(mode.value()));
        }
        assertEquals(Optional.empty(), PermissionMode.fromValue("nonexistent_mode"));
    }

    @Test
    public void reasoningEffortCoversProtocolValues() {
        // C2:ReasoningEffort SSOT 全集 5 档(= Claude API 全集;Codex/HAIKU 子集由展示层过滤)
        assertEquals("low", ReasoningEffort.LOW.value());
        assertEquals("medium", ReasoningEffort.MEDIUM.value());
        assertEquals("high", ReasoningEffort.HIGH.value());
        assertEquals("xhigh", ReasoningEffort.XHIGH.value());
        assertEquals("max", ReasoningEffort.MAX.value());
    }

    @Test
    public void reasoningEffortFromValueRoundTrip() {
        for (ReasoningEffort effort : ReasoningEffort.values()) {
            assertEquals(Optional.of(effort), ReasoningEffort.fromValue(effort.value()));
        }
        assertEquals(Optional.empty(), ReasoningEffort.fromValue("nonexistent_effort"));
    }

    @Test
    public void providerTypeCoversProtocolValues() {
        // C2/C9:ProviderType SSOT 值域 2 值(对齐 CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX)
        assertEquals("claude", ProviderType.CLAUDE.value());
        assertEquals("codex", ProviderType.CODEX.value());
    }

    @Test
    public void providerTypeFromValueRoundTrip() {
        for (ProviderType type : ProviderType.values()) {
            assertEquals(Optional.of(type), ProviderType.fromValue(type.value()));
        }
        assertEquals(Optional.empty(), ProviderType.fromValue("nonexistent_provider"));
    }

    private static void assertUnique(List<String> values) {
        assertTrue("duplicate values: " + values, new HashSet<>(values).size() == values.size());
    }
}
