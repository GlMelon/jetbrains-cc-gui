package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexHistoryPageResult;
import com.github.claudecodegui.provider.codex.CodexHistoryService;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.protocol.payload.CodexHistoryPageErrorPayloadField;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/** Loads and prepends one persisted Codex history page. */
public class LoadCodexHistoryPageActionHandler implements FrontendActionHandler<CodexHistoryPageRequest> {
    @Override
    public UpstreamAction action() {
        return UpstreamAction.LOAD_CODEX_HISTORY_PAGE;
    }

    @Override
    public Class<CodexHistoryPageRequest> payloadType() {
        return CodexHistoryPageRequest.class;
    }

    @Override
    public void handle(CodexHistoryPageRequest payload, FrontendActionContext context) {
        HandlerContext handlerContext = context.handlerContext();
        String requestedSessionId = payload == null ? null : normalize(payload.sessionId());
        if (requestedSessionId == null) {
            dispatchError(handlerContext, null, "sessionId is required");
            return;
        }
        Integer beforeTurn = payload.beforeTurn();
        if (beforeTurn == null) {
            dispatchError(handlerContext, requestedSessionId, "beforeTurn is required");
            return;
        }
        if (beforeTurn < 0) {
            dispatchError(handlerContext, requestedSessionId, "beforeTurn must be non-negative");
            return;
        }
        if (!ProviderType.CODEX.value().equals(handlerContext.getCurrentProvider())) {
            dispatchError(handlerContext, requestedSessionId, "Codex history pagination is unavailable for the active provider");
            return;
        }

        ClaudeSession session = handlerContext.getSession();
        if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
            dispatchError(handlerContext, requestedSessionId, "The requested session is no longer active");
            return;
        }

        CodexHistoryService historyService = new CodexHistoryService();
        CompletableFuture.supplyAsync(
                () -> historyService.loadHistoryPage(requestedSessionId, beforeTurn),
                AppExecutorUtil.getAppExecutorService()
        ).whenComplete((result, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
                return;
            }
            if (error != null) {
                dispatchError(handlerContext, requestedSessionId, errorMessage(error));
                return;
            }
            applyResult(handlerContext, session, requestedSessionId, result);
        }));
    }

    private static void applyResult(
            HandlerContext handlerContext,
            ClaudeSession session,
            String requestedSessionId,
            CodexHistoryPageResult result
    ) {
        session.applyCodexHistoryPage(result.messages(), CodexHistoryPageMode.PREPEND);
        if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
            return;
        }
        handlerContext.dispatchEvent(
                DownstreamEvent.HISTORY_CODEX_PAGE_INFO.value(),
                handlerContext.escapeJs(GsonHolder.GSON.toJson(result.pageInfo()))
        );
    }

    static boolean isCurrentSession(HandlerContext context, ClaudeSession expectedSession, String sessionId) {
        return expectedSession != null
                && context.getSession() == expectedSession
                && sessionId.equals(expectedSession.getSessionId())
                && ProviderType.CODEX.value().equals(expectedSession.getProvider())
                && ProviderType.CODEX.value().equals(context.getCurrentProvider());
    }

    private static void dispatchError(HandlerContext context, String sessionId, String error) {
        JsonObject payload = new JsonObject();
        if (sessionId != null) {
            payload.addProperty(CodexHistoryPageErrorPayloadField.SESSION_ID.wireKey(), sessionId);
        }
        payload.addProperty(CodexHistoryPageErrorPayloadField.ERROR.wireKey(), error);
        context.dispatchEvent(
                DownstreamEvent.HISTORY_CODEX_PAGE_ERROR.value(),
                context.escapeJs(GsonHolder.GSON.toJson(payload))
        );
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String errorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
