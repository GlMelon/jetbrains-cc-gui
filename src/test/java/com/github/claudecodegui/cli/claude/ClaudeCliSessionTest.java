package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.McpErrorMatcher;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClaudeCliSessionTest {

    @Test
    public void cliEntrypointNormalizationRunsAfterProcessExitWithoutEnvironmentWorkaround() throws Exception {
        String source = Files.readString(Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "cli", "claude", "ClaudeCliSession.java"
        ));

        int processExitIndex = source.indexOf("int exitCode = process.exitValue()");
        int rewriteIndex = source.indexOf("this.normalizeCliSessionEntrypoint(request)");

        assertTrue(processExitIndex >= 0);
        assertTrue(rewriteIndex > processExitIndex);
        assertFalse(source.contains("ENV_CLAUDE_CODE_ENTRYPOINT"));
        assertTrue(source.contains("Set.of(SessionEntrypoint.SDK_CLI)"));
    }

    @Test
    public void buildCommandDoesNotSendEffortForUnknownCustomModels() throws Exception {
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(
                "mimo-v2.5-pro", new JsonObject());

        List<String> command = buildCommand(request("mimo-v2.5-pro", "high"), profile);

        assertTrue(command.contains("--model"));
        assertTrue(command.contains("mimo-v2.5-pro"));
        assertFalse(command.contains("--effort"));
        assertFalse(command.contains("high"));
    }

    @Test
    public void requestModelEnvironmentOverridesStaleSonnetMappingForCustomModel() {
        Map<String, String> env = new HashMap<>();
        env.put(CommonConstants.ENV_ANTHROPIC_MODEL, "glm-5.2");
        env.put(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL, "glm-5.2[1M]");
        CliSendRequest request = request("mimo-v2.5", null, "mimo-v2.5");
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(
                request.model(),
                new JsonObject()
        );

        ClaudeCliSession.configureRequestModelEnvironment(env, request, profile);

        assertEquals("mimo-v2.5", env.get(CommonConstants.ENV_ANTHROPIC_MODEL));
        assertEquals("mimo-v2.5", env.get(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL));
    }

    @Test
    public void buildCommandSendsEffortForCanonicalClaudeModels() throws Exception {
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(
                "claude-sonnet-4-6", new JsonObject());

        List<String> command = buildCommand(request("claude-sonnet-4-6", "high"), profile);

        assertTrue(command.contains("--model"));
        assertTrue(command.contains("claude-sonnet-4-6"));
        assertTrue(command.contains("--effort"));
        assertTrue(command.contains("high"));
    }

    @Test
    public void buildExitErrorWrapsServiceUnavailableDiagnostic() throws Exception {
        Method method = ClaudeCliSession.class.getDeclaredMethod(
                "buildExitError",
                int.class,
                StringBuilder.class
        );
        method.setAccessible(true);

        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("unexpected status 503 Service Unavailable: Service temporarily unavailable, ")
                .append("url: https://gongyiapi.mossx.ai/responses, ")
                .append("request id: req-claude-503");

        String error = (String) method.invoke(null, 1, diagnostic);

        assertTrue(error.contains("Claude CLI 请求失败"));
        assertTrue(error.contains("服务暂时不可用 (503)"));
        assertTrue(error.contains("https://gongyiapi.mossx.ai/responses"));
        assertTrue(error.contains("req-claude-503"));
    }

    @Test
    public void buildCommandOmitsDisabledOptionalCliCapabilities() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL_CAPABILITIES", "no-effort,no-mcp,no-add-dir,no-partial-messages");
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(
                "claude-sonnet-4-6", env);

        List<String> command = buildCommand(
                request("claude-sonnet-4-6", "high"),
                profile,
                true,
                "C:/tmp/mcp.json",
                List.of("C:/tmp/images")
        );

        assertFalse(command.contains("--effort"));
        assertFalse(command.contains("--mcp-config"));
        assertFalse(command.contains("--add-dir"));
        assertFalse(command.contains("--include-partial-messages"));
    }

    @Test
    public void interruptBeforeActiveHandleStillMarksSessionInterrupted() {
        ClaudeCliSession session = new ClaudeCliSession("tab-claude");

        session.interrupt();

        assertTrue(session.wasInterrupted());
    }

    @Test
    public void interruptedExitEmitsInterruptedCompletionOnlyOnce() {
        ClaudeCliSession session = new ClaudeCliSession("tab-claude");
        AtomicBoolean interruptHandled = new AtomicBoolean(false);

        session.interrupt();

        assertTrue(session.shouldEmitInterruptedCompletion(interruptHandled));
        interruptHandled.set(true);
        assertFalse(session.shouldEmitInterruptedCompletion(interruptHandled));
    }

    @Test
    public void interruptedExitNeverReportsExitCodeErrorEvenAfterInterruptedCompletionWasHandled() {
        ClaudeCliSession session = new ClaudeCliSession("tab-claude");
        AtomicBoolean interruptHandled = new AtomicBoolean(true);

        session.interrupt();

        assertFalse(session.shouldEmitInterruptedCompletion(interruptHandled));
        assertFalse(session.shouldReportExitError(1, false));
    }

    @Test
    public void sendPreparationClearsOnlyPreviousInterrupts() {
        ClaudeCliSession session = new ClaudeCliSession("tab-claude");

        session.interrupt();
        session.prepareForSend();
        assertFalse(session.wasInterrupted());

        session.interrupt();
        assertTrue(session.wasInterrupted());
    }

    private static List<String> buildCommand(
            CliSendRequest request,
            ClaudeCliModelResolver.ResolvedModel profile
    ) {
        return buildCommand(request, profile, false, null, List.of());
    }

    private static List<String> buildCommand(
            CliSendRequest request,
            ClaudeCliModelResolver.ResolvedModel profile,
            boolean hasMcpServers,
            String mcpConfigFilePath,
            List<String> addDirs
    ) {
        return ClaudeCliSession.buildCommand(
                "claude",
                request,
                addDirs,
                profile,
                hasMcpServers,
                mcpConfigFilePath,
                null
        );
    }

    private static CliSendRequest request(String model, String reasoningEffort) {
        return request(model, reasoningEffort, null);
    }

    private static CliSendRequest request(String model, String reasoningEffort, String actualModel) {
        return new CliSendRequest(
                "tab-claude",
                "claude",
                "hello",
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                "default",
                model,
                actualModel,
                reasoningEffort,
                null,
                Map.of()
        );
    }

    @Test
    public void mcpFailureDowngradesToNonBlockingStatusNotice() {
        // 本地 MCP server 未启动时,Claude CLI 输出 mcp_servers_failed_to_connect 等名。
        // 该错误不应让回合失败:降级为非阻塞 status toast(对称 Codex handleMcpFailure)。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-mcp");
        RecordingCallback callback = new RecordingCallback();

        boolean suppressed = session.handleMcpFailure(
                "Error: mcp_servers_failed_to_connect: weather", callback);

        assertTrue("MCP 连接失败应被 handleMcpFailure 抑制(不计入回合错误)", suppressed);
        assertEquals("应发一条非阻塞 status 提示", 1,
                callback.contentsOfType(CliConstants.CODEX_MSG_STATUS).size());
        assertEquals("status 提示文案为 MCP_SKIPPED_NOTICE", McpErrorMatcher.MCP_SKIPPED_NOTICE,
                callback.contentsOfType(CliConstants.CODEX_MSG_STATUS).get(0));
        assertTrue("MCP 失败不得 onError", callback.errors.isEmpty());
    }

    @Test
    public void mcpFailureNoticeEmittedAtMostOncePerTurn() {
        // rmcp / mcp worker 可能重试多次刷屏:每回合只发一次 toast,但每次匹配都抑制。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-mcp-dedupe");
        RecordingCallback callback = new RecordingCallback();
        String mcpError = "Error: mcp server 'weather' failed to connect";

        session.handleMcpFailure(mcpError, callback);
        session.handleMcpFailure(mcpError, callback);
        session.handleMcpFailure(mcpError, callback);

        assertEquals("多次 MCP 错误只发一次 status toast", 1,
                callback.contentsOfType(CliConstants.CODEX_MSG_STATUS).size());
    }

    @Test
    public void nonMcpErrorNotSuppressedByHandleMcpFailure() {
        // 致命错误(502 重连死循环)与普通错误不得被 MCP 抑制:留给既有致命/exit 错误处理。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-non-mcp");
        RecordingCallback callback = new RecordingCallback();

        boolean suppressed = session.handleMcpFailure(
                "unexpected status 502 Bad Gateway", callback);

        assertFalse("非 MCP 错误不得被抑制", suppressed);
        assertTrue("非 MCP 错误不发 status toast",
                callback.contentsOfType(CliConstants.CODEX_MSG_STATUS).isEmpty());
    }

    @Test
    public void maybeResetResetsPollutedNonUuidSessionId() throws Exception {
        // 防御纵深:跨 provider 污染(OpenCode ses_ 前缀)若绕过 setProvider 隔离再次混入,
        // Claude CLI 报 "ses_xxx is not a UUID" 并崩。格式校验应使其自愈。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-uuid-guard");
        java.lang.reflect.Field sessionIdField = ClaudeCliSession.class.getDeclaredField("sessionId");
        sessionIdField.setAccessible(true);
        sessionIdField.set(session, "ses_01JabcDEFghijklmnopQRSTuvwx");

        invokeMaybeReset(session, "Error: ses_01JabcDEFghijklmnopQRSTuvwx is not a UUID");

        assertNull("非 UUID 的污染 sessionId 应被重置为 null", sessionIdField.get(session));
    }

    @Test
    public void maybeResetResetsOnNotAUuidKeywordEvenForValidLookingId() throws Exception {
        // 关键词覆盖:即便格式恰好是 UUID(假设未来 CLI 文案变化),诊断含 "not a uuid" 也应重置。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-keyword");
        java.lang.reflect.Field sessionIdField = ClaudeCliSession.class.getDeclaredField("sessionId");
        sessionIdField.setAccessible(true);
        String validUuid = "12345678-1234-1234-1234-123456789012";
        sessionIdField.set(session, validUuid);

        invokeMaybeReset(session, "resume: " + validUuid + " is not a UUID");

        assertNull("诊断含 'not a uuid' 关键词应重置 sessionId", sessionIdField.get(session));
    }

    @Test
    public void maybeResetPreservesValidUuidOnUnrelatedDiagnostic() throws Exception {
        // 合法 UUID 且诊断无关 resume 失败时,sessionId 不得误清。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-preserve");
        java.lang.reflect.Field sessionIdField = ClaudeCliSession.class.getDeclaredField("sessionId");
        sessionIdField.setAccessible(true);
        String validUuid = "12345678-1234-1234-1234-123456789012";
        sessionIdField.set(session, validUuid);

        invokeMaybeReset(session, "unexpected status 502 Bad Gateway");

        assertEquals("合法 UUID + 无关诊断不应重置", validUuid, sessionIdField.get(session));
    }

    private static void invokeMaybeReset(ClaudeCliSession session, String diagnostic) throws Exception {
        Method method = ClaudeCliSession.class.getDeclaredMethod(
                "maybeResetSessionAfterResumeFailure", CharSequence.class);
        method.setAccessible(true);
        method.invoke(session, (CharSequence) new StringBuilder(diagnostic));
    }

    @Test
    public void prepareForSendResetsMcpNoticeDedup() {
        // 每回合重置去重:新回合的 MCP 失败应再次提示(而非永久静默)。
        ClaudeCliSession session = new ClaudeCliSession("tab-claude-mcp-reset");
        RecordingCallback callback = new RecordingCallback();
        String mcpError = "Error: mcp_servers_failed_to_connect: weather";

        session.handleMcpFailure(mcpError, callback);
        session.prepareForSend();
        session.handleMcpFailure(mcpError, callback);

        assertEquals("prepareForSend 后 MCP 去重应重置,新回合再发一次提示", 2,
                callback.contentsOfType(CliConstants.CODEX_MSG_STATUS).size());
    }

    private static final class RecordingCallback implements CliSessionCallback {
        private final List<Event> messages = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(new Event(type, content));
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }

        @Override
        public void onComplete(boolean success, String finalResult, String error) {
        }

        private List<String> contentsOfType(String type) {
            List<String> values = new ArrayList<>();
            for (Event event : messages) {
                if (type.equals(event.type)) {
                    values.add(event.content);
                }
            }
            return values;
        }
    }

    private static final class Event {
        private final String type;
        private final String content;

        private Event(String type, String content) {
            this.type = type;
            this.content = content;
        }
    }
}
