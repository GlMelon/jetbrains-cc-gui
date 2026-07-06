package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Permission action handlers container.
 * Holds shared state (pending request maps) and provides dialog-showing methods
 * for PermissionService, as well as response-handling methods for typed handlers.
 */
public class PermissionActionHandlers {

    private static final Logger LOG = Logger.getInstance(PermissionActionHandlers.class);

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
        String channelId = UUID.randomUUID().toString();
        CompletableFuture<Integer> future = new CompletableFuture<>();

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
                LOG.info("[PERM_SHOW] Executing JS to show dialog for channelId=" + channelId);
                showDialogWithFrontendCheck(
                    () -> "window.showPermissionDialog('" + escapedJson + "');",
                    () -> "(function retryShowDialog(retries) { " +
                        "  if (window.showPermissionDialog) { " +
                        "    window.showPermissionDialog('" + escapedJson + "'); " +
                        "  } else if (retries > 0) { " +
                        "    setTimeout(function() { retryShowDialog(retries - 1); }, 200); " +
                        "  } else { " +
                        "    console.error('[PERM_DEBUG][JS] FAILED: showPermissionDialog not available!'); " +
                        "  } " +
                        "})(30);",
                    "[PERM_SHOW]"
                );
            });

            scheduleSafetyNet(future, () -> {
                if (future.complete(PermissionService.PermissionResponse.DENY.getValue())) {
                    LOG.warn("[PERM_SHOW] Safety-net timeout fired (webview unreachable) for channelId=" + channelId);
                    pendingPermissionRequests.remove(channelId);
                    // The webview may still have the dialog open (with its own
                    // longer countdown finishing later, or stuck in an invisible
                    // state from a JCEF render issue). Tell it to drop the
                    // current dialog so the queue can drain for the next
                    // request — see issue #1360.
                    forceCloseFrontendDialog("forceClosePermissionDialog", channelId);
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
     * Show permission request dialog (from PermissionRequest).
     */
    public void showPermissionDialog(PermissionRequest request) {
        LOG.info("[PermissionActionHandlers] 显示权限请求对话框: " + request.getToolName());

        try {
            Gson gson = GsonHolder.GSON;
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", request.getChannelId());
            requestData.addProperty("toolName", request.getToolName());

            JsonObject inputsJson = gson.toJsonTree(request.getInputs()).getAsJsonObject();
            requestData.add("inputs", inputsJson);

            if (request.getSuggestions() != null) {
                requestData.add("suggestions", request.getSuggestions());
            }

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            Project targetProject = request.getProject();
            if (targetProject == null) {
                LOG.warn("[PermissionActionHandlers] 警告: PermissionRequest 没有关联的 Project，使用当前 context 的窗口");
                targetProject = this.context.getProject();
            }

            com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow targetWindow =
                com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow.getChatWindow(targetProject);

            if (targetWindow == null) {
                LOG.error("[PermissionActionHandlers] Error: cannot find window instance for project " + targetProject.getName());
                this.context.getSession().handlePermissionDecision(
                    request.getChannelId(),
                    false,
                    false,
                    "Failed to show permission dialog: window not found"
                );
                notifyPermissionDenied();
                return;
            }

            String jsCode = "if (window.showPermissionDialog) { " +
                "  window.showPermissionDialog('" + escapedJson + "'); " +
                "}";

            targetWindow.executeJavaScriptCode(jsCode);

        } catch (Exception e) {
            LOG.error("[PermissionActionHandlers] 显示权限弹窗失败: errorClass=" + e.getClass().getSimpleName(), e);
            this.context.getSession().handlePermissionDecision(
                request.getChannelId(),
                false,
                false,
                "Failed to show permission dialog"
            );
            notifyPermissionDenied();
        }
    }

    /**
     * Show AskUserQuestion dialog.
     */
    public CompletableFuture<JsonObject> showAskUserQuestionDialog(String requestId, JsonObject questionsData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Starting showAskUserQuestionDialog");
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] requestId=" + requestId);

        pendingAskUserQuestionRequests.put(requestId, future);

        try {
            Gson gson = GsonHolder.GSON;
            String requestJson = gson.toJson(questionsData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                showDialogWithFrontendCheck(
                    () -> "window.showAskUserQuestionDialog('" + escapedJson + "');",
                    () -> "(function retryShowAskUserQuestion(retries) { " +
                        "  if (window.showAskUserQuestionDialog) { " +
                        "    window.showAskUserQuestionDialog('" + escapedJson + "'); " +
                        "  } else if (retries > 0) { " +
                        "    setTimeout(function() { retryShowAskUserQuestion(retries - 1); }, 200); " +
                        "  } else { " +
                        "    console.error('[ASK_USER_QUESTION][JS] FAILED: showAskUserQuestionDialog not available!'); " +
                        "  } " +
                        "})(30);",
                    "[ASK_USER_QUESTION][SHOW_DIALOG]"
                );
            });

            scheduleSafetyNet(future, () -> {
                if (future.complete(new JsonObject())) {
                    LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingAskUserQuestionRequests.remove(requestId);
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
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] Starting showPlanApprovalDialog");
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] requestId=" + requestId);

        pendingPlanApprovalRequests.put(requestId, future);

        try {
            Gson gson = GsonHolder.GSON;
            String requestJson = gson.toJson(planData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                showDialogWithFrontendCheck(
                    () -> "window.showPlanApprovalDialog('" + escapedJson + "');",
                    () -> "(function retryShowPlanApproval(retries) { " +
                        "  if (window.showPlanApprovalDialog) { " +
                        "    window.showPlanApprovalDialog('" + escapedJson + "'); " +
                        "  } else if (retries > 0) { " +
                        "    setTimeout(function() { retryShowPlanApproval(retries - 1); }, 200); " +
                        "  } else { " +
                        "    console.error('[PLAN_APPROVAL][JS] FAILED: showPlanApprovalDialog not available!'); " +
                        "  } " +
                        "})(30);",
                    "[PLAN_APPROVAL][SHOW_DIALOG]"
                );
            });

            scheduleSafetyNet(future, () -> {
                JsonObject timeoutResponse = new JsonObject();
                timeoutResponse.addProperty("approved", false);
                timeoutResponse.addProperty("targetMode", CommonConstants.PERMISSION_MODE_DEFAULT);
                timeoutResponse.addProperty("message", "Plan approval timed out");
                if (future.complete(timeoutResponse)) {
                    LOG.warn("[PLAN_APPROVAL][SHOW_DIALOG] Safety-net timeout fired (webview unreachable) for requestId=" + requestId);
                    pendingPlanApprovalRequests.remove(requestId);
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
                if (remember) {
                    context.getSession().handlePermissionDecisionAlways(channelId, allow);
                } else {
                    context.getSession().handlePermissionDecision(channelId, allow, false, rejectMessage);
                }
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

        int permissionCount = pendingPermissionRequests.size();
        int askUserCount = pendingAskUserQuestionRequests.size();
        int planCount = pendingPlanApprovalRequests.size();

        for (Map.Entry<String, CompletableFuture<Integer>> entry : pendingPermissionRequests.entrySet()) {
            entry.getValue().complete(PermissionService.PermissionResponse.DENY.getValue());
        }
        pendingPermissionRequests.clear();

        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingAskUserQuestionRequests.entrySet()) {
            entry.getValue().complete(null);
        }
        pendingAskUserQuestionRequests.clear();

        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingPlanApprovalRequests.entrySet()) {
            JsonObject rejected = new JsonObject();
            rejected.addProperty("approved", false);
            rejected.addProperty("message", "Session changed");
            entry.getValue().complete(rejected);
        }
        pendingPlanApprovalRequests.clear();

        LOG.info("[PERM_CLEAR] Cleared: " + permissionCount + " permission, " +
                 askUserCount + " askUser, " + planCount + " plan requests");
    }

    // --- Internal helpers ---

    private void notifyPermissionDenied() {
        if (deniedCallback != null) {
            deniedCallback.onPermissionDenied();
        }
    }

    private void showDialogWithFrontendCheck(
            java.util.function.Supplier<String> directJsSupplier,
            java.util.function.Supplier<String> fallbackJsSupplier,
            String logPrefix) {
        final int maxWaitAttempts = 50;
        final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

        Runnable checkAndShow = new Runnable() {
            private int waitAttempts = 0;

            @Override
            public void run() {
                if (context.isFrontendReady()) {
                    LOG.debug(logPrefix + " Frontend ready, showing dialog directly");
                    context.executeJavaScriptOnEDT(directJsSupplier.get());
                } else if (waitAttempts < maxWaitAttempts) {
                    waitAttempts++;
                    LOG.debug(logPrefix + " Frontend not ready, waiting... attempt " + waitAttempts);
                    alarm.addRequest(this, 200);
                } else {
                    LOG.warn(logPrefix + " Frontend not ready after max wait attempts, trying JavaScript fallback");
                    context.executeJavaScriptOnEDT(fallbackJsSupplier.get());
                }
            }
        };

        checkAndShow.run();
    }

    private String escapeJs(String json) {
        return context.escapeJs(json);
    }
}
