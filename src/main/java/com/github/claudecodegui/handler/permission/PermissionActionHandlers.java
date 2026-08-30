package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Permission action handlers container.
 * Holds shared state (pending request maps) and provides dialog-showing methods
 * for PermissionService, as well as response-handling methods for typed handlers.
 */
public class PermissionActionHandlers implements Disposable {

    private static final Logger LOG = Logger.getInstance(PermissionActionHandlers.class);
    private static final String PERMISSION_REQUEST_KEY_PREFIX = "permission:";
    private static final String ASK_USER_REQUEST_KEY_PREFIX = "ask-user:";
    private static final String PLAN_APPROVAL_REQUEST_KEY_PREFIX = "plan-approval:";
    private static final int FRONTEND_READY_MAX_WAIT_ATTEMPTS = 50;
    private static final int FRONTEND_READY_RETRY_DELAY_MILLIS = 200;

    // --- Safety net scheduler (testable) ---

    public interface CancellableTask {
        void cancel();
    }

    public interface SafetyNetScheduler {
        CancellableTask schedule(Runnable task, long delaySeconds);
    }

    static final SafetyNetScheduler DEFAULT_SAFETY_NET_SCHEDULER = (task, delaySeconds) -> {
        ScheduledFuture<?> scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(task, delaySeconds, TimeUnit.SECONDS);
        return () -> scheduledFuture.cancel(false);
    };

    private final HandlerContext context;
    private final SafetyNetScheduler safetyNetScheduler;
    private final Disposable lifecycleDisposable = Disposer.newDisposable("PermissionActionHandlers");
    private final Alarm frontendReadyAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, lifecycleDisposable);
    private final Map<String, Runnable> pendingFrontendChecks = new ConcurrentHashMap<>();
    private final AtomicLong requestGeneration = new AtomicLong();
    private volatile boolean disposed;

    // --- Pending request maps ---

    private final Map<String, CompletableFuture<Integer>> pendingPermissionRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> pendingAskUserQuestionRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> pendingPlanApprovalRequests = new ConcurrentHashMap<>();

    // --- Permission denied callback ---

    public interface PermissionDeniedCallback {
        void onPermissionDenied();
    }

    private PermissionDeniedCallback deniedCallback;

    public PermissionActionHandlers(HandlerContext context) {
        this(context, DEFAULT_SAFETY_NET_SCHEDULER);
    }

    PermissionActionHandlers(HandlerContext context, SafetyNetScheduler safetyNetScheduler) {
        this.context = context;
        this.safetyNetScheduler = safetyNetScheduler;
    }

    public void setPermissionDeniedCallback(PermissionDeniedCallback callback) {
        this.deniedCallback = callback;
    }

    // --- Safety net helpers ---

    long getSafetyNetTimeoutSeconds() {
        if (context == null) {
            return CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
        CodemossSettingsService settingsService = context.getSettingsService();
        if (settingsService == null) {
            return CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
        try {
            return settingsService.getPermissionDialogTimeoutSeconds()
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        } catch (Exception e) {
            LOG.warn("[PERM_SHOW] Failed to read permission dialog timeout for safety net; errorClass="
                    + e.getClass().getSimpleName(), e);
            return CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
                    + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        }
    }

    void scheduleSafetyNet(CompletableFuture<?> future, Runnable timeoutTask) {
        CancellableTask cancellableTask = safetyNetScheduler.schedule(timeoutTask, getSafetyNetTimeoutSeconds());
        future.whenComplete((ignored, error) -> cancellableTask.cancel());
    }

    // --- Dialog-showing methods (called by PermissionService) ---

    /**
     * Show the frontend permission dialog.
     */
    public CompletableFuture<Integer> showFrontendPermissionDialog(String toolName, JsonObject inputs) {
        if (disposed || context == null) {
            return CompletableFuture.completedFuture(PermissionService.PermissionResponse.DENY.getValue());
        }

        String channelId = UUID.randomUUID().toString();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        String requestKey = PERMISSION_REQUEST_KEY_PREFIX + channelId;
        Object expectedSession = context != null ? context.getSession() : null;
        long generation = requestGeneration.get();

        LOG.info("[PERM_SHOW] showFrontendPermissionDialog called: channelId=" + channelId + ", toolName=" + toolName);

        pendingPermissionRequests.put(channelId, future);
        LOG.info("[PERM_SHOW] Stored pending request, total pending: " + pendingPermissionRequests.size());

        try {
            Gson gson = GsonHolder.GSON;
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", channelId);
            requestData.addProperty("toolName", toolName);
            requestData.add("inputs", inputs);

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!isRequestActive(requestKey, future, expectedSession, generation,
                        () -> pendingPermissionRequests.get(channelId) == future)) {
                    return;
                }
                LOG.info("[PERM_SHOW] Executing JS to show dialog for channelId=" + channelId);
                showDialogWithFrontendCheck(
                    () -> "window.showPermissionDialog('" + escapedJson + "');",
                    () -> "if (window.showPermissionDialog) { " +
                        "window.showPermissionDialog('" + escapedJson + "'); " +
                        "} else { console.error('[PERM_DEBUG][JS] FAILED: showPermissionDialog not available!'); }",
                    "[PERM_SHOW]", requestKey, future, expectedSession, generation,
                    () -> pendingPermissionRequests.get(channelId) == future
                );
            });

            scheduleSafetyNet(future, () -> {
                if (future.complete(PermissionService.PermissionResponse.DENY.getValue())) {
                    LOG.warn("[PERM_SHOW] Safety-net timeout fired (webview unreachable) for channelId=" + channelId);
                    pendingPermissionRequests.remove(channelId, future);
                }
            });

        } catch (Exception e) {
            LOG.error("[PERM_SHOW] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
            pendingPermissionRequests.remove(channelId);
            future.complete(PermissionService.PermissionResponse.DENY.getValue());
        }

        return future;
    }

    /**
     * Show AskUserQuestion dialog.
     */
    public CompletableFuture<JsonObject> showAskUserQuestionDialog(String requestId, JsonObject questionsData) {
        if (disposed || context == null) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String requestKey = ASK_USER_REQUEST_KEY_PREFIX + requestId;
        Object expectedSession = context != null ? context.getSession() : null;
        long generation = requestGeneration.get();

        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Starting showAskUserQuestionDialog");
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] requestId=" + requestId);

        CompletableFuture<JsonObject> previous = pendingAskUserQuestionRequests.put(requestId, future);
        if (previous != null) {
            previous.complete(null);
        }

        try {
            Gson gson = GsonHolder.GSON;
            String requestJson = gson.toJson(questionsData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!isRequestActive(requestKey, future, expectedSession, generation,
                        () -> pendingAskUserQuestionRequests.get(requestId) == future)) {
                    return;
                }
                showDialogWithFrontendCheck(
                    () -> "window.showAskUserQuestionDialog('" + escapedJson + "');",
                    () -> "if (window.showAskUserQuestionDialog) { " +
                        "window.showAskUserQuestionDialog('" + escapedJson + "'); " +
                        "} else { console.error('[ASK_USER_QUESTION][JS] FAILED: showAskUserQuestionDialog not available!'); }",
                    "[ASK_USER_QUESTION][SHOW_DIALOG]", requestKey, future, expectedSession, generation,
                    () -> pendingAskUserQuestionRequests.get(requestId) == future
                );
            });

            scheduleSafetyNet(future, () -> {
                if (future.complete(new JsonObject())) {
                    LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingAskUserQuestionRequests.remove(requestId, future);
                }
            });

        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][SHOW_DIALOG] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
            pendingAskUserQuestionRequests.remove(requestId);
            future.complete(new JsonObject());
        }

        return future;
    }

    /**
     * Show PlanApproval dialog.
     */
    public CompletableFuture<JsonObject> showPlanApprovalDialog(String requestId, JsonObject planData) {
        if (disposed || context == null) {
            JsonObject rejected = new JsonObject();
            rejected.addProperty("approved", false);
            rejected.addProperty("targetMode", CommonConstants.PERMISSION_MODE_DEFAULT);
            rejected.addProperty("message", "Permission handler disposed");
            return CompletableFuture.completedFuture(rejected);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String requestKey = PLAN_APPROVAL_REQUEST_KEY_PREFIX + requestId;
        Object expectedSession = context != null ? context.getSession() : null;
        long generation = requestGeneration.get();

        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] Starting showPlanApprovalDialog");
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] requestId=" + requestId);

        CompletableFuture<JsonObject> previous = pendingPlanApprovalRequests.put(requestId, future);
        if (previous != null) {
            previous.complete(null);
        }

        try {
            Gson gson = GsonHolder.GSON;
            String requestJson = gson.toJson(planData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!isRequestActive(requestKey, future, expectedSession, generation,
                        () -> pendingPlanApprovalRequests.get(requestId) == future)) {
                    return;
                }
                showDialogWithFrontendCheck(
                    () -> "window.showPlanApprovalDialog('" + escapedJson + "');",
                    () -> "if (window.showPlanApprovalDialog) { " +
                        "window.showPlanApprovalDialog('" + escapedJson + "'); " +
                        "} else { console.error('[PLAN_APPROVAL][JS] FAILED: showPlanApprovalDialog not available!'); }",
                    "[PLAN_APPROVAL][SHOW_DIALOG]", requestKey, future, expectedSession, generation,
                    () -> pendingPlanApprovalRequests.get(requestId) == future
                );
            });

            scheduleSafetyNet(future, () -> {
                JsonObject timeoutResponse = new JsonObject();
                timeoutResponse.addProperty("approved", false);
                timeoutResponse.addProperty("targetMode", CommonConstants.PERMISSION_MODE_DEFAULT);
                timeoutResponse.addProperty("message", "Plan approval timed out");
                if (future.complete(timeoutResponse)) {
                    LOG.warn("[PLAN_APPROVAL][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingPlanApprovalRequests.remove(requestId, future);
                }
            });

        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][SHOW_DIALOG] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
            pendingPlanApprovalRequests.remove(requestId);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("approved", false);
            errorResponse.addProperty("targetMode", CommonConstants.PERMISSION_MODE_DEFAULT);
            errorResponse.addProperty("message", "Error showing plan approval dialog");
            future.complete(errorResponse);
        }

        return future;
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handlePermissionDecision(String jsonContent) {
        LOG.info("[PERM_DECISION] Received permission decision from JS");
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject decision = gson.fromJson(jsonContent, JsonObject.class);

            String channelId = decision.get("channelId").getAsString();
            boolean allow = decision.get("allow").getAsBoolean();
            boolean remember = decision.get("remember").getAsBoolean();
            String rejectMessage = "";
            if (decision.has("rejectMessage") && !decision.get("rejectMessage").isJsonNull()) {
                rejectMessage = decision.get("rejectMessage").getAsString();
            }

            LOG.info("[PERM_DECISION] channelId=" + channelId + ", allow=" + allow + ", remember=" + remember);

            CompletableFuture<Integer> pendingFuture = pendingPermissionRequests.remove(channelId);

            if (pendingFuture != null) {
                int responseValue;
                if (allow) {
                    responseValue = remember ?
                        PermissionService.PermissionResponse.ALLOW_ALWAYS.getValue() :
                        PermissionService.PermissionResponse.ALLOW.getValue();
                } else {
                    responseValue = PermissionService.PermissionResponse.DENY.getValue();
                }
                pendingFuture.complete(responseValue);

                if (!allow) {
                    notifyPermissionDenied();
                }
            } else {
                LOG.warn("[PERM_DECISION] No pending future found for channelId=" + channelId);
                if (!allow) {
                    notifyPermissionDenied();
                }
            }
        } catch (Exception e) {
            LOG.error("[PERM_DECISION] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
        }
    }

    void handleAskUserQuestionResponse(String jsonContent) {
        LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Received response from JS");
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);

            String requestId = response.get("requestId").getAsString();
            JsonObject answers = response.has("answers") && !response.get("answers").isJsonNull()
                ? response.get("answers").getAsJsonObject()
                : new JsonObject();

            CompletableFuture<JsonObject> pendingFuture = pendingAskUserQuestionRequests.remove(requestId);

            if (pendingFuture != null) {
                LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Completing future with answerCount=" + answers.size());
                pendingFuture.complete(answers);
            } else {
                LOG.warn("[ASK_USER_QUESTION][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][HANDLE_RESPONSE] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
        }
    }

    void handlePlanApprovalResponse(String jsonContent) {
        LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Received response from JS");
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);

            String requestId = response.get("requestId").getAsString();
            boolean approved = response.has("approved") && response.get("approved").getAsBoolean();
            String targetMode = response.has("targetMode") ? response.get("targetMode").getAsString() : CommonConstants.PERMISSION_MODE_DEFAULT;

            CompletableFuture<JsonObject> pendingFuture = pendingPlanApprovalRequests.remove(requestId);

            if (pendingFuture != null) {
                JsonObject result = new JsonObject();
                result.addProperty("approved", approved);
                result.addProperty("targetMode", targetMode);
                LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Completing future: approved=" + approved + ", targetMode=" + targetMode);
                pendingFuture.complete(result);
            } else {
                LOG.warn("[PLAN_APPROVAL][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][HANDLE_RESPONSE] ERROR: errorClass=" + e.getClass().getSimpleName(), e);
        }
    }

    // --- Cleanup ---

    /**
     * Clear all pending permission requests.
     * Called during session switching or history restoration.
     */
    public void clearPendingRequests() {
        LOG.info("[PERM_CLEAR] Clearing all pending permission requests");
        requestGeneration.incrementAndGet();
        cancelAllFrontendChecks();

        int permissionCount = pendingPermissionRequests.size();
        int askUserCount = pendingAskUserQuestionRequests.size();
        int planCount = pendingPlanApprovalRequests.size();

        for (Map.Entry<String, CompletableFuture<Integer>> entry : pendingPermissionRequests.entrySet()) {
            if (pendingPermissionRequests.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().complete(PermissionService.PermissionResponse.DENY.getValue());
            }
        }

        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingAskUserQuestionRequests.entrySet()) {
            if (pendingAskUserQuestionRequests.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().complete(null);
            }
        }

        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingPlanApprovalRequests.entrySet()) {
            if (pendingPlanApprovalRequests.remove(entry.getKey(), entry.getValue())) {
                JsonObject rejected = new JsonObject();
                rejected.addProperty("approved", false);
                rejected.addProperty("message", "Session changed");
                entry.getValue().complete(rejected);
            }
        }

        LOG.info("[PERM_CLEAR] Cleared: " + permissionCount + " permission, " +
                 askUserCount + " askUser, " + planCount + " plan requests");
    }

    // --- Internal helpers ---

    /**
     * Return the number of requests that are still waiting for a frontend response.
     * This is intentionally package-visible so lifecycle tests can observe that
     * session switches and disposal do not leave pending requests behind.
     */
    int getPendingRequestCount() {
        return pendingPermissionRequests.size()
                + pendingAskUserQuestionRequests.size()
                + pendingPlanApprovalRequests.size();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        clearPendingRequests();
        frontendReadyAlarm.cancelAllRequests();
        Disposer.dispose(lifecycleDisposable);
    }

    private boolean isRequestActive(
            String requestKey,
            CompletableFuture<?> future,
            Object expectedSession,
            long generation,
            BooleanSupplier requestStillPending) {
        if (disposed || future.isDone() || requestGeneration.get() != generation
                || !requestStillPending.getAsBoolean()) {
            return false;
        }
        if (context == null) {
            return false;
        }
        return !context.isDisposed() && context.getSession() == expectedSession;
    }

    private void cancelAllFrontendChecks() {
        for (Map.Entry<String, Runnable> entry : pendingFrontendChecks.entrySet()) {
            if (pendingFrontendChecks.remove(entry.getKey(), entry.getValue())) {
                frontendReadyAlarm.cancelRequest(entry.getValue());
            }
        }
    }

    private void cancelFrontendCheck(String requestKey, Runnable checkAndShow) {
        if (pendingFrontendChecks.remove(requestKey, checkAndShow)) {
            frontendReadyAlarm.cancelRequest(checkAndShow);
        }
    }

    private void notifyPermissionDenied() {
        if (deniedCallback != null) {
            deniedCallback.onPermissionDenied();
        }
    }

    private void showDialogWithFrontendCheck(
            java.util.function.Supplier<String> directJsSupplier,
            java.util.function.Supplier<String> fallbackJsSupplier,
            String logPrefix,
            String requestKey,
            CompletableFuture<?> future,
            Object expectedSession,
            long generation,
            BooleanSupplier requestStillPending) {

        Runnable checkAndShow = new Runnable() {
            private int waitAttempts = 0;

            @Override
            public void run() {
                if (!isRequestActive(requestKey, future, expectedSession, generation, requestStillPending)) {
                    cancelFrontendCheck(requestKey, this);
                    return;
                }
                if (context.isFrontendReady()) {
                    LOG.debug(logPrefix + " Frontend ready, showing dialog directly");
                    cancelFrontendCheck(requestKey, this);
                    context.executeJavaScriptOnEDT(directJsSupplier.get());
                } else if (waitAttempts < FRONTEND_READY_MAX_WAIT_ATTEMPTS) {
                    waitAttempts++;
                    LOG.debug(logPrefix + " Frontend not ready, waiting... attempt " + waitAttempts);
                    frontendReadyAlarm.addRequest(this, FRONTEND_READY_RETRY_DELAY_MILLIS);
                } else {
                    LOG.warn(logPrefix + " Frontend not ready after max wait attempts, trying JavaScript fallback");
                    cancelFrontendCheck(requestKey, this);
                    context.executeJavaScriptOnEDT(fallbackJsSupplier.get());
                }
            }
        };

        pendingFrontendChecks.put(requestKey, checkAndShow);
        future.whenComplete((ignored, error) -> cancelFrontendCheck(requestKey, checkAndShow));
        checkAndShow.run();
    }

    private String escapeJs(String json) {
        return context != null ? context.escapeJs(json) : json;
    }
}
