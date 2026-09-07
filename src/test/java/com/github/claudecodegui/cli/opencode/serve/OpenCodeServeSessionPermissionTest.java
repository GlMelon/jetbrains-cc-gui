package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeServeSession 交互式权限拦截测试(JDK HttpServer 记录权限应答 HTTP 调用):
 * <ul>
 *   <li>bypass 模式不经闸口直接 "always";</li>
 *   <li>非 bypass 经 ServePermissionGate 决策(once/always/reject 透传),闸口异常兜底 "reject";</li>
 *   <li>闸口缺失(无 Project 路径)退回保守 MVP:非 bypass 一律 "reject";</li>
 *   <li>permission.asked 缺 permissionId 时应答不可达,事件被吞掉。</li>
 * </ul>
 */
public class OpenCodeServeSessionPermissionTest {

    private HttpServer server;
    private OpenCodeServeClient client;
    private final CountDownLatch permissionResponded = new CountDownLatch(1);
    private final AtomicReference<String> respondedBody = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/ses_1/permissions/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            respondedBody.set(new String(body, StandardCharsets.UTF_8));
            permissionResponded.countDown();
            respondJson(exchange, 200, "{}");
        });
        server.start();
        client = new OpenCodeServeClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class NoopCallback implements CliSessionCallback {
        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
        }
    }

    private static CliSendRequest newRequest(String permissionMode) {
        return new CliSendRequest("tab-1", CommonConstants.PROVIDER_OPENCODE, "hi",
                null, null, null, null, null, null, permissionMode, null, null, null, null, null);
    }

    private static JsonObject permissionAskedEvent(String permissionId) {
        JsonObject properties = new JsonObject();
        if (permissionId != null) {
            properties.addProperty("id", permissionId);
        }
        properties.addProperty("sessionID", "ses_1");
        properties.addProperty("permission", "bash");
        JsonObject metadata = new JsonObject();
        metadata.addProperty("command", "ls -la");
        properties.add("metadata", metadata);
        JsonObject event = new JsonObject();
        event.addProperty("type", OpenCodeServeTurn.EVENT_PERMISSION_ASKED);
        event.add("properties", properties);
        return event;
    }

    private OpenCodeServeClient.TurnEventHandler newInterceptHandler(
            String permissionMode, OpenCodeServeSession.ServePermissionGate gate) {
        OpenCodeServeSession session = new OpenCodeServeSession("tab-1", client, null, gate);
        return session.wrapWithPermissionIntercept(
                client, "ses_1", new OpenCodeServeTurn(new NoopCallback()), newRequest(permissionMode));
    }

    private String awaitRespondedResponse() throws Exception {
        assertTrue("permission response should be posted", permissionResponded.await(10, TimeUnit.SECONDS));
        JsonObject body = JsonParser.parseString(respondedBody.get()).getAsJsonObject();
        return body.get("response").getAsString();
    }

    @Test
    public void bypassModeRespondsAlwaysWithoutGate() throws Exception {
        AtomicReference<String> gateTool = new AtomicReference<>();
        OpenCodeServeClient.TurnEventHandler handler = newInterceptHandler(
                CommonConstants.PERMISSION_MODE_BYPASS,
                (toolName, inputs, cwd) -> {
                    gateTool.set(toolName);
                    return CompletableFuture.completedFuture("reject");
                });
        handler.onEvent(permissionAskedEvent("perm_1"));
        assertEquals("always", awaitRespondedResponse());
        assertNull("bypass must not consult the gate", gateTool.get());
    }

    @Test
    public void gateDecisionIsPassedThroughWithToolAndInputs() throws Exception {
        AtomicReference<String> gateTool = new AtomicReference<>();
        AtomicReference<JsonObject> gateInputs = new AtomicReference<>();
        OpenCodeServeClient.TurnEventHandler handler = newInterceptHandler(null,
                (toolName, inputs, cwd) -> {
                    gateTool.set(toolName);
                    gateInputs.set(inputs);
                    return CompletableFuture.completedFuture("once");
                });
        handler.onEvent(permissionAskedEvent("perm_2"));
        assertEquals("once", awaitRespondedResponse());
        assertEquals("bash", gateTool.get());
        assertEquals("ls -la", gateInputs.get().get("command").getAsString());
    }

    @Test
    public void gateFailureFallsBackToReject() throws Exception {
        OpenCodeServeClient.TurnEventHandler handler = newInterceptHandler(null,
                (toolName, inputs, cwd) -> {
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("dialog unreachable"));
                    return failed;
                });
        handler.onEvent(permissionAskedEvent("perm_3"));
        assertEquals("reject", awaitRespondedResponse());
    }

    @Test
    public void missingGateKeepsConservativeMvpReject() throws Exception {
        OpenCodeServeClient.TurnEventHandler handler = newInterceptHandler(null, null);
        handler.onEvent(permissionAskedEvent("perm_4"));
        assertEquals("reject", awaitRespondedResponse());
    }

    @Test
    public void eventWithoutPermissionIdIsSwallowed() throws Exception {
        OpenCodeServeClient.TurnEventHandler handler = newInterceptHandler(null,
                (toolName, inputs, cwd) -> CompletableFuture.completedFuture("once"));
        handler.onEvent(permissionAskedEvent(null));
        assertTrue("no permission response should be posted",
                permissionResponded.await(1, TimeUnit.SECONDS) == false);
        assertNull(respondedBody.get());
    }
}
