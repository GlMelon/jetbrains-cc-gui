package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CodexAppServerSession 降级与流转单测(经 FakeAppServer 管道对接真实 client):
 * 降级语义对齐 OpenCodeServeSessionFallbackTest(递交前失败 → one-shot;
 * 递交后失败 → 只报错不重发)+ 审批拦截(对齐 OpenCodeServeSessionPermissionTest)。
 */
public class CodexAppServerSessionTest {

    private FakeAppServer fake;

    @Before
    public void setUp() throws Exception {
        fake = new FakeAppServer();
    }

    @After
    public void tearDown() {
        fake.close();
    }

    private static CliSendRequest request(String permissionMode) {
        return new CliSendRequest("tab1", "codex", "hello", null, "D:/tmp",
                List.of(), null, List.of(), null, permissionMode, null, null, null, null, null, null);
    }

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();
        volatile boolean success;
        volatile String finalResult;
        volatile String error;

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String e) {
            error = e;
        }

        @Override
        public void onComplete(boolean s, String fullContent, String e) {
            success = s;
            finalResult = fullContent;
            error = e;
        }
    }

    private static final class RecordingFallback implements CliSession {
        final AtomicInteger sends = new AtomicInteger();
        volatile CliSendRequest lastRequest;

        @Override
        public CompletableFuture<Void> send(CliSendRequest req, CliSessionCallback callback) {
            sends.incrementAndGet();
            lastRequest = req;
            callback.onComplete(true, "fallback", null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void dispose() {
        }
    }

    private static JsonObject threadResult(String threadId) {
        JsonObject thread = new JsonObject();
        thread.addProperty("id", threadId);
        JsonObject result = new JsonObject();
        result.add("thread", thread);
        return result;
    }

    private static JsonObject turnResult(String turnId) {
        JsonObject turn = new JsonObject();
        turn.addProperty("id", turnId);
        JsonObject result = new JsonObject();
        result.add("turn", turn);
        return result;
    }

    @Test
    public void threadStartFailureDegradesToOneShot() throws Exception {
        // thread/start 确定性错误响应(server 拒绝,prompt 从未到达)→ 当轮降级 one-shot
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.errorResponse("spawn failed");
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.errorResponse("resume failed");
            }
            return null;
        });
        RecordingFallback fallback = new RecordingFallback();
        CodexAppServerSession session = new CodexAppServerSession("tab1", fake.createClient(), fallback);
        RecordingCallback cb = new RecordingCallback();

        session.send(request(null), cb).get(20, TimeUnit.SECONDS);

        assertEquals(1, fallback.sends.get());
        assertEquals("fallback", cb.finalResult);
    }

    @Test
    public void turnStartErrorResponseDegradesToOneShot() throws Exception {
        // thread/start 成功、turn/start 确定性错误响应(turn 未被接受,重发安全)→ 降级 one-shot
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.errorResponse("turn rejected");
            }
            return null;
        });
        RecordingFallback fallback = new RecordingFallback();
        CodexAppServerSession session = new CodexAppServerSession("tab1", fake.createClient(), fallback);
        RecordingCallback cb = new RecordingCallback();

        session.send(request(null), cb).get(20, TimeUnit.SECONDS);

        assertEquals(1, fallback.sends.get());
        assertEquals("fallback", cb.finalResult);
    }

    @Test
    public void happyPathStreamsDeltasAndCompletes() throws Exception {
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        // turn/start 后推送事件:等 handler 注册完成(轮询 receivedRequests)
        new Thread(() -> {
            try {
                Thread.sleep(200);
                JsonObject params = new JsonObject();
                params.addProperty("threadId", "t-1");
                params.addProperty("turnId", "u-1");
                params.addProperty("itemId", "i1");
                params.addProperty("delta", "Hello");
                fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_AGENT_MESSAGE_DELTA, params);
                params.addProperty("delta", " world");
                fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_AGENT_MESSAGE_DELTA, params);
                JsonObject turn = new JsonObject();
                turn.addProperty("id", "u-1");
                turn.addProperty("status", "completed");
                JsonObject completed = new JsonObject();
                completed.addProperty("threadId", "t-1");
                completed.add("turn", turn);
                fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED, completed);
            } catch (Exception ignored) {
            }
        }, "HappyPath-Notifier").start();

        RecordingFallback fallback = new RecordingFallback();
        CodexAppServerSession session = new CodexAppServerSession("tab1", fake.createClient(), fallback);
        RecordingCallback cb = new RecordingCallback();

        session.send(request(null), cb).get(20, TimeUnit.SECONDS);

        assertEquals(0, fallback.sends.get());
        assertTrue(cb.success);
        assertEquals("Hello world", cb.finalResult);
        long contentDeltas = cb.messages.stream()
                .filter(m -> m[0].equals(CliConstants.MSG_CONTENT_DELTA)).count();
        assertEquals(2, contentDeltas);
        long sessionIds = cb.messages.stream()
                .filter(m -> m[0].equals(CliConstants.MSG_SESSION_ID)).count();
        assertEquals(1, sessionIds);
    }

    @Test
    public void approvalRequestRoutesThroughGateAndRespondsDecision() throws Exception {
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        AtomicReference<String> askedTool = new AtomicReference<>();
        AtomicReference<JsonObject> askedInputs = new AtomicReference<>();
        CodexAppServerSession.AppServerPermissionGate gate = (toolName, inputs, cwd) -> {
            askedTool.set(toolName);
            askedInputs.set(inputs);
            return CompletableFuture.completedFuture(CliConstants.CODEX_APPSERVER_DECISION_ACCEPT);
        };
        CodexAppServerSession session =
                new CodexAppServerSession("tab1", fake.createClient(), new RecordingFallback(), gate);
        RecordingCallback cb = new RecordingCallback();

        CompletableFuture<Void> sent = session.send(request(null), cb);
        Thread.sleep(300); // 等 turn handler 注册
        JsonObject params = new JsonObject();
        params.addProperty("threadId", "t-1");
        params.addProperty("turnId", "u-1");
        params.addProperty("command", "rm -rf /tmp/x");
        long requestId = fake.sendServerRequest(
                CliConstants.CODEX_APPSERVER_REQUEST_COMMAND_APPROVAL, params);
        Thread.sleep(300); // 等决策应答

        assertEquals("Bash", askedTool.get());
        assertEquals("rm -rf /tmp/x", askedInputs.get().get("command").getAsString());
        JsonObject response = fake.receivedResponses().get(requestId);
        assertEquals(CliConstants.CODEX_APPSERVER_DECISION_ACCEPT,
                response.getAsJsonObject("result").get("decision").getAsString());

        // 收尾:推送 turn completed 结束轮
        JsonObject turn = new JsonObject();
        turn.addProperty("id", "u-1");
        turn.addProperty("status", "completed");
        JsonObject completed = new JsonObject();
        completed.addProperty("threadId", "t-1");
        completed.add("turn", turn);
        fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED, completed);
        sent.get(20, TimeUnit.SECONDS);
        assertTrue(cb.success);
    }

    @Test
    public void bypassModeRespondsAcceptForSessionWithoutGate() throws Exception {
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        // 闸口缺失(测试 seam 无 gate)+ bypass 模式 → acceptForSession
        CodexAppServerSession session =
                new CodexAppServerSession("tab1", fake.createClient(), new RecordingFallback());
        RecordingCallback cb = new RecordingCallback();

        CompletableFuture<Void> sent = session.send(request(CommonConstants.PERMISSION_MODE_BYPASS), cb);
        Thread.sleep(300);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", "t-1");
        params.addProperty("turnId", "u-1");
        params.addProperty("command", "echo hi");
        long requestId = fake.sendServerRequest(
                CliConstants.CODEX_APPSERVER_REQUEST_COMMAND_APPROVAL, params);
        Thread.sleep(300);

        JsonObject response = fake.receivedResponses().get(requestId);
        assertEquals(CliConstants.CODEX_APPSERVER_DECISION_ACCEPT_FOR_SESSION,
                response.getAsJsonObject("result").get("decision").getAsString());

        JsonObject turn = new JsonObject();
        turn.addProperty("id", "u-1");
        turn.addProperty("status", "completed");
        JsonObject completed = new JsonObject();
        completed.addProperty("threadId", "t-1");
        completed.add("turn", turn);
        fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED, completed);
        sent.get(20, TimeUnit.SECONDS);
    }

    @Test
    public void nonApprovalServerRequestGetsMethodNotFound() throws Exception {
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        CodexAppServerSession session =
                new CodexAppServerSession("tab1", fake.createClient(), new RecordingFallback());
        RecordingCallback cb = new RecordingCallback();

        CompletableFuture<Void> sent = session.send(request(null), cb);
        Thread.sleep(300);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", "t-1");
        params.addProperty("turnId", "u-1");
        long requestId = fake.sendServerRequest("item/unknown/requestApproval", params);
        Thread.sleep(300);

        JsonObject response = fake.receivedResponses().get(requestId);
        assertTrue(response.has("error"));
        assertTrue(response.getAsJsonObject("error").get("code").getAsInt() < 0);

        JsonObject turn = new JsonObject();
        turn.addProperty("id", "u-1");
        turn.addProperty("status", "completed");
        JsonObject completed = new JsonObject();
        completed.addProperty("threadId", "t-1");
        completed.add("turn", turn);
        fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED, completed);
        sent.get(20, TimeUnit.SECONDS);
        assertNull(cb.error);
    }

    @Test
    public void turnStartAckAloneDoesNotCompleteTheTurn() throws Exception {
        // ack 不终结 turn(锁上游 025386b0 家族教训的对应不变量):turn/start 响应只回填
        // turnId / 提交标记,轮的收尾只认 turn/completed 通知或进程死亡——否则 ack 后的
        // 等待期会被误判为完成,流式事件全部丢失
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        CodexAppServerSession session =
                new CodexAppServerSession("tab1", fake.createClient(), new RecordingFallback());
        RecordingCallback cb = new RecordingCallback();

        CompletableFuture<Void> sent = session.send(request(null), cb);
        Thread.sleep(800); // ack 已返回、等待通知窗口:完成/失败回调都不应已发生

        assertNull("ack alone must not complete the turn", cb.error);
        assertNull("ack alone must not deliver a final result", cb.finalResult);

        JsonObject turn = new JsonObject();
        turn.addProperty("id", "u-1");
        turn.addProperty("status", "completed");
        JsonObject completed = new JsonObject();
        completed.addProperty("threadId", "t-1");
        completed.add("turn", turn);
        fake.sendNotification(CliConstants.CODEX_APPSERVER_NOTIFY_TURN_COMPLETED, completed);
        sent.get(20, TimeUnit.SECONDS);
        assertTrue(cb.success);
    }

    @Test
    public void submittedTurnProcessDeathFailsWithoutDegrading() throws Exception {
        // 已递交轮(ack 已返回)进程死亡 → 只报错不降级 one-shot(防双发;提交语义见
        // handleTurnFailure)——锁上游 1d78be55 类「中断/收尾期重发循环」教训的对应不变量
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        RecordingFallback fallback = new RecordingFallback();
        CodexAppServerSession session = new CodexAppServerSession("tab1", fake.createClient(), fallback);
        RecordingCallback cb = new RecordingCallback();

        CompletableFuture<Void> sent = session.send(request(null), cb);
        Thread.sleep(500); // 等 thread/start + turn/start 双 ack 落地(轮已递交)
        fake.close();      // stdout EOF → 已递交轮失败收尾

        sent.get(20, TimeUnit.SECONDS);
        assertEquals("submitted turn must not be re-sent via one-shot", 0, fallback.sends.get());
        assertTrue(cb.error != null && !cb.success);
    }

    @Test
    public void silentTurnTimesOutAfterTheIdleWindow() throws Exception {
        // 空闲感知 watchdog(对称 one-shot awaitIdleAware):递交后无任何 server 产出的轮,
        // 连续无产出超过空闲窗口才判挂死报错;有产出的轮由活跃戳放行(anyServerOutput*
        // 测试覆盖刷新语义)。注入小窗口使测试不必等真实 CLI_IDLE_TIMEOUT_MS。
        fake.setHandler(method -> {
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_START.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME.equals(method)) {
                return FakeAppServer.okResponse(threadResult("t-1"));
            }
            if (CliConstants.CODEX_APPSERVER_METHOD_TURN_START.equals(method)) {
                return FakeAppServer.okResponse(turnResult("u-1"));
            }
            return null;
        });
        RecordingFallback fallback = new RecordingFallback();
        CodexAppServerSession session = new CodexAppServerSession("tab1", fake.createClient(), fallback);
        session.setTurnIdleTimeoutForTest(300);
        RecordingCallback cb = new RecordingCallback();

        session.send(request(null), cb).get(20, TimeUnit.SECONDS);

        // 已递交(ack 返回):空闲超时只报错,不降级防双发
        assertEquals(0, fallback.sends.get());
        assertTrue(cb.error != null && cb.error.contains("timed out"));
        assertFalse(cb.success);
    }
}
