package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.settings.CodemossSettingsService;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexSDKBridgeEnvTest {

    @Test
    public void configureProviderEnvDoesNotInjectUseStdinFlag() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-env");
        try {
            TestCodexSDKBridge bridge = new TestCodexSDKBridge(sessionsDir);
            Map<String, String> env = new LinkedHashMap<>();

            bridge.applyProviderEnv(env, "{}");

            assertFalse(env.containsKey("CODEX_USE_STDIN"));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void populateCodexRuntimeEnvDoesNotInjectUseStdinFlag() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-runtime-env");
        try {
            CodexSDKBridge bridge = new CodexSDKBridge(sessionsDir, new EnvironmentConfigurator(new CodemossSettingsService()), new CodemossSettingsService());
            Map<String, String> env = new LinkedHashMap<>();

            Method method = CodexSDKBridge.class.getDeclaredMethod(
                    "populateCodexRuntimeEnv",
                    Map.class,
                    String.class,
                    String.class,
                    String.class,
                    String.class
            );
            method.setAccessible(true);
            method.invoke(bridge, env, "/workspace", "default", "codex-mini", "message");

            assertFalse(env.containsKey("CODEX_USE_STDIN"));
            assertTrue(env.containsKey("CODEX_MODEL"));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void populateCodexRuntimeEnvMapsPlanToReadOnlySuggestMode() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-plan-env");
        try {
            CodexSDKBridge bridge = new CodexSDKBridge(sessionsDir, new EnvironmentConfigurator(new CodemossSettingsService()), new CodemossSettingsService());
            Map<String, String> env = populateRuntimeEnv(bridge, "plan");

            assertEquals("read-only", env.get("CODEX_SANDBOX_MODE"));
            assertEquals("read-only", env.get("CODEX_SANDBOX"));
            assertEquals("untrusted", env.get("CODEX_APPROVAL_POLICY"));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void populateCodexRuntimeEnvMapsBypassToNeverFullAccessMode() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-bypass-env");
        try {
            CodexSDKBridge bridge = new CodexSDKBridge(sessionsDir, new EnvironmentConfigurator(new CodemossSettingsService()), new CodemossSettingsService());
            Map<String, String> env = populateRuntimeEnv(bridge, "bypassPermissions");

            assertEquals("danger-full-access", env.get("CODEX_SANDBOX_MODE"));
            assertEquals("danger-full-access", env.get("CODEX_SANDBOX"));
            assertEquals("never", env.get("CODEX_APPROVAL_POLICY"));
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    @Test
    public void resolveReasoningEffortUsesSsotDefaultWhenNull() throws Exception {
        // C3 守门:reasoningEffort 为 null 时兜底必须来自全局 SSOT 常量,
        // 不得再出现独立的 "medium" 字面量(曾与全局 "high" 默认漂移)。
        Method method = CodexSDKBridge.class.getDeclaredMethod("resolveReasoningEffort", String.class);
        method.setAccessible(true);

        // null(SessionState 默认 / GitCommitMessageService 显式 null) → 全局 SSOT 默认
        assertEquals(CommonConstants.DEFAULT_REASONING_EFFORT, method.invoke(null, new Object[]{null}));
        // 非空 → 原样透传
        assertEquals("low", method.invoke(null, "low"));
        assertEquals("medium", method.invoke(null, "medium"));
        assertEquals("high", method.invoke(null, "high"));
    }

    @Test
    public void defaultReasoningEffortConstantRemainsHigh() {
        // C3 守门:SSOT 默认值固定 high,防止被静默改回 medium 造成再次漂移
        assertEquals("high", CommonConstants.DEFAULT_REASONING_EFFORT);
    }

    @Test
    public void processOutputLineIgnoresCodexStatusMessages() throws Exception {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-status");
        try {
            TestCodexSDKBridge bridge = new TestCodexSDKBridge(sessionsDir);
            RecordingCallback callback = new RecordingCallback();
            SDKResult result = new SDKResult();

            bridge.processLine(
                    "[MESSAGE] {\"type\":\"status\",\"message\":\"正在执行命令: git status\"}",
                    callback,
                    result
            );

            assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type)));
            assertTrue(result.messages.isEmpty());
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    private static Map<String, String> populateRuntimeEnv(CodexSDKBridge bridge, String permissionMode) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        Method method = CodexSDKBridge.class.getDeclaredMethod(
                "populateCodexRuntimeEnv",
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(bridge, env, "/workspace", permissionMode, "codex-mini", "message");
        return env;
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static class TestCodexSDKBridge extends CodexSDKBridge {
        TestCodexSDKBridge(Path sessionsDir) {
            super(sessionsDir, new EnvironmentConfigurator(new CodemossSettingsService()), new CodemossSettingsService());
        }

        void applyProviderEnv(Map<String, String> env, String stdinJson) {
            super.configureProviderEnv(env, stdinJson);
        }

        void processLine(String line, MessageCallback callback, SDKResult result) {
            super.processOutputLine(
                    line,
                    callback,
                    result,
                    new StringBuilder(),
                    new AtomicBoolean(false),
                    new AtomicReference<>()
            );
        }
    }

    private static final class RecordingCallback implements MessageCallback {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            events.add(new Event(type, content));
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(SDKResult result) {
        }
    }

    private record Event(String type, String content) {
    }
}
