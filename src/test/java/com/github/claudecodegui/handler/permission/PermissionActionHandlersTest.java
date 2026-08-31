package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.service.PendingInteractionDiagnosticsService;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PermissionActionHandlers}.
 *
 * <p>Covers the response-handling paths and the session-change safety net
 * ({@code clearPendingRequests}). Pending-request maps are populated via
 * reflection so we can exercise the response paths without going through the EDT.</p>
 */
public class PermissionActionHandlersTest {

    private PermissionActionHandlers handlers;

    @Before
    public void setUp() {
        handlers = new PermissionActionHandlers(contextStub());
    }

    @After
    public void tearDown() {
        handlers.dispose();
    }

    // --- Permission decision tests ---

    @Test
    public void handlePermissionDecisionCompletesAllowFuture() throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        injectPermissionFuture("ch-allow", future);

        String content = "{\"channelId\":\"ch-allow\",\"allow\":true,\"remember\":false}";
        handlers.handlePermissionDecision(content);

        Integer result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PermissionService.PermissionResponse.ALLOW.getValue(), result.intValue());
        assertTrue("future should be removed from map after dispatch", getPermissionMap().isEmpty());
    }

    @Test
    public void handlePermissionDecisionCompletesAllowAlwaysFuture() throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        injectPermissionFuture("ch-allow-always", future);

        String content = "{\"channelId\":\"ch-allow-always\",\"allow\":true,\"remember\":true}";
        handlers.handlePermissionDecision(content);

        Integer result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PermissionService.PermissionResponse.ALLOW_ALWAYS.getValue(), result.intValue());
    }

    @Test
    public void handlePermissionDecisionCompletesDenyFuture() throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        injectPermissionFuture("ch-deny", future);

        String content = "{\"channelId\":\"ch-deny\",\"allow\":false,\"remember\":false}";
        handlers.handlePermissionDecision(content);

        Integer result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PermissionService.PermissionResponse.DENY.getValue(), result.intValue());
    }

    // --- AskUserQuestion response tests ---

    @Test
    public void handleAskUserQuestionResponseCompletesFuture() throws Exception {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        injectAskUserFuture("auq-1", future);

        String content = "{\"requestId\":\"auq-1\",\"answers\":{\"color\":\"red\"}}";
        handlers.handleAskUserQuestionResponse(content);

        JsonObject result = future.get(2, TimeUnit.SECONDS);
        assertEquals("red", result.get("color").getAsString());
        assertTrue(getAskUserMap().isEmpty());
    }

    // --- PlanApproval response tests ---

    @Test
    public void handlePlanApprovalResponseCompletesFuture() throws Exception {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        injectPlanApprovalFuture("plan-1", future);

        String content = "{\"requestId\":\"plan-1\",\"approved\":true,\"targetMode\":\"default\"}";
        handlers.handlePlanApprovalResponse(content);

        JsonObject result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result.get("approved").getAsBoolean());
        assertEquals("default", result.get("targetMode").getAsString());
        assertTrue(getPlanApprovalMap().isEmpty());
    }

    @Test
    public void pendingDiagnosticsFollowAskUserQuestionLifecycle() throws Exception {
        RecordingSource diagnosticsSource = new RecordingSource();
        PermissionActionHandlers diagnosticHandlers = new PermissionActionHandlers(
                contextStub(),
                (task, delaySeconds) -> () -> { },
                diagnosticsSource);
        try {
            assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), diagnosticsSource.latest);

            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            injectAskUserFuture(diagnosticHandlers, "diagnostic-ask", future);
            publishPendingDiagnostics(diagnosticHandlers);
            assertEquals(1, diagnosticsSource.latest.pendingPermissionRequests());

            diagnosticHandlers.handleAskUserQuestionResponse(
                    "{\"requestId\":\"diagnostic-ask\",\"answers\":{\"answer\":\"yes\"}}");

            assertEquals("yes", future.get(2, TimeUnit.SECONDS).get("answer").getAsString());
            assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), diagnosticsSource.latest);
        } finally {
            diagnosticHandlers.dispose();
        }

        assertTrue(diagnosticsSource.closed);
        assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), diagnosticsSource.latest);
    }

    // --- clearPendingRequests tests ---

    @Test
    public void clearPendingRequestsCompletesAllPermissionFuturesWithDeny() throws Exception {
        CompletableFuture<Integer> f1 = new CompletableFuture<>();
        CompletableFuture<Integer> f2 = new CompletableFuture<>();
        injectPermissionFuture("ch-1", f1);
        injectPermissionFuture("ch-2", f2);

        handlers.clearPendingRequests();

        assertEquals(PermissionService.PermissionResponse.DENY.getValue(),
                f1.get(1, TimeUnit.SECONDS).intValue());
        assertEquals(PermissionService.PermissionResponse.DENY.getValue(),
                f2.get(1, TimeUnit.SECONDS).intValue());
        assertTrue(getPermissionMap().isEmpty());
    }

    @Test
    public void clearPendingRequestsCompletesAskUserFuturesWithNull() throws Exception {
        CompletableFuture<JsonObject> f1 = new CompletableFuture<>();
        injectAskUserFuture("auq-1", f1);

        handlers.clearPendingRequests();

        assertNull(f1.get(1, TimeUnit.SECONDS));
        assertTrue(getAskUserMap().isEmpty());
    }

    @Test
    public void clearPendingRequestsCompletesPlanApprovalFuturesWithRejected() throws Exception {
        CompletableFuture<JsonObject> f1 = new CompletableFuture<>();
        injectPlanApprovalFuture("plan-1", f1);

        handlers.clearPendingRequests();

        JsonObject result = f1.get(1, TimeUnit.SECONDS);
        assertFalse(result.get("approved").getAsBoolean());
        assertEquals("Session changed", result.get("message").getAsString());
        assertTrue(getPlanApprovalMap().isEmpty());
    }

    @Test
    public void clearPendingRequestsCancelsTrackedFrontendChecks() throws Exception {
        Runnable check = () -> { };
        getFrontendChecks().put("permission:stale", check);

        handlers.clearPendingRequests();

        assertTrue(getFrontendChecks().isEmpty());
        assertEquals(0, handlers.getPendingRequestCount());
    }

    @Test
    public void disposeClearsPendingRequestsAndIsIdempotent() throws Exception {
        CompletableFuture<Integer> permissionFuture = new CompletableFuture<>();
        CompletableFuture<JsonObject> askFuture = new CompletableFuture<>();
        CompletableFuture<JsonObject> planFuture = new CompletableFuture<>();
        injectPermissionFuture("dispose-permission", permissionFuture);
        injectAskUserFuture("dispose-ask", askFuture);
        injectPlanApprovalFuture("dispose-plan", planFuture);
        getFrontendChecks().put("permission:dispose", () -> { });

        handlers.dispose();
        handlers.dispose();

        assertEquals(PermissionService.PermissionResponse.DENY.getValue(),
                permissionFuture.get(1, TimeUnit.SECONDS).intValue());
        assertNull(askFuture.get(1, TimeUnit.SECONDS));
        JsonObject planResult = planFuture.get(1, TimeUnit.SECONDS);
        assertFalse(planResult.get("approved").getAsBoolean());
        assertTrue(getPermissionMap().isEmpty());
        assertTrue(getAskUserMap().isEmpty());
        assertTrue(getPlanApprovalMap().isEmpty());
        assertTrue(getFrontendChecks().isEmpty());
        assertEquals(0, handlers.getPendingRequestCount());
    }

    private static final class RecordingSource implements PendingInteractionDiagnosticsService.Source {
        private PendingInteractionDiagnosticsService.Snapshot latest =
                PendingInteractionDiagnosticsService.Snapshot.empty();
        private boolean closed;

        @Override
        public void update(PendingInteractionDiagnosticsService.Snapshot snapshot) {
            latest = snapshot;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // --- Reflection helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Runnable> getFrontendChecks() throws Exception {
        Field f = PermissionActionHandlers.class.getDeclaredField("pendingFrontendChecks");
        f.setAccessible(true);
        return (Map<String, Runnable>) f.get(handlers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, CompletableFuture<Integer>> getPermissionMap() throws Exception {
        Field f = PermissionActionHandlers.class.getDeclaredField("pendingPermissionRequests");
        f.setAccessible(true);
        return (Map<String, CompletableFuture<Integer>>) f.get(handlers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, CompletableFuture<JsonObject>> getAskUserMap() throws Exception {
        Field f = PermissionActionHandlers.class.getDeclaredField("pendingAskUserQuestionRequests");
        f.setAccessible(true);
        return (Map<String, CompletableFuture<JsonObject>>) f.get(handlers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, CompletableFuture<JsonObject>> getPlanApprovalMap() throws Exception {
        Field f = PermissionActionHandlers.class.getDeclaredField("pendingPlanApprovalRequests");
        f.setAccessible(true);
        return (Map<String, CompletableFuture<JsonObject>>) f.get(handlers);
    }

    private void injectPermissionFuture(String channelId, CompletableFuture<Integer> future) throws Exception {
        getPermissionMap().put(channelId, future);
    }

    private void injectAskUserFuture(String requestId, CompletableFuture<JsonObject> future) throws Exception {
        injectAskUserFuture(handlers, requestId, future);
    }

    @SuppressWarnings("unchecked")
    private static void injectAskUserFuture(
            PermissionActionHandlers target,
            String requestId,
            CompletableFuture<JsonObject> future
    ) throws Exception {
        Field field = PermissionActionHandlers.class.getDeclaredField(
                "pendingAskUserQuestionRequests");
        field.setAccessible(true);
        ((Map<String, CompletableFuture<JsonObject>>) field.get(target)).put(requestId, future);
    }

    private static void publishPendingDiagnostics(PermissionActionHandlers target) throws Exception {
        var method = PermissionActionHandlers.class.getDeclaredMethod("publishPendingDiagnostics");
        method.setAccessible(true);
        method.invoke(target);
    }

    private void injectPlanApprovalFuture(String requestId, CompletableFuture<JsonObject> future) throws Exception {
        getPlanApprovalMap().put(requestId, future);
    }

    private static HandlerContext contextStub() {
        return new HandlerContext(
                null,
                null,
                new HandlerContext.JsCallback() {
                    @Override public void callJavaScript(String functionName, String... args) {}
                    @Override public String escapeJs(String str) { return str; }
                }
        );
    }
}
