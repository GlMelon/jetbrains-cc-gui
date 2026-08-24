package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.provider.claude.ClaudeHistoryService;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Loads and prepends one persisted history page. 已通用化:CODEX 与 CLAUDE 按 currentProvider
 * 路由(切片语义两端一致,见 {@code CodexHistoryPageService} / {@code ClaudeHistoryPageService});
 * action/wire 名保留 codex 兼容既有前端契约,事件与 payload 字段 provider 无关。
 */
public class LoadCodexHistoryPageActionHandler implements FrontendActionHandler<CodexHistoryPageRequest> {
    /** 支持磁盘分页的 provider 白名单(与 isCurrentSession 的 provider 校验同源)。 */
    private static final Set<String> PAGINATION_PROVIDERS = Set.of(
            ProviderType.CODEX.value(), ProviderType.CLAUDE.value());

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
        String currentProvider = handlerContext.getCurrentProvider();
        if (!PAGINATION_PROVIDERS.contains(currentProvider)) {
            dispatchError(handlerContext, requestedSessionId,
                    "History pagination is unavailable for the active provider: " + currentProvider);
            return;
        }

        ClaudeSession session = handlerContext.getSession();
        if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
            dispatchError(handlerContext, requestedSessionId, "The requested session is no longer active");
            return;
        }

        CompletableFuture<SessionHistoryLoadResult> pageFuture =
                ProviderType.CODEX.value().equals(currentProvider)
                        ? CompletableFuture.supplyAsync(
                                () -> {
                                    CodexHistoryPageResult page =
                                            new CodexHistoryService().loadHistoryPage(requestedSessionId, beforeTurn);
                                    return new SessionHistoryLoadResult(page.messages(), page.pageInfo());
                                },
                                AppExecutorUtil.getAppExecutorService())
                        : supplyClaudePage(session, requestedSessionId, beforeTurn);
        pageFuture.whenComplete((result, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
                return;
            }
            if (error != null) {
                dispatchError(handlerContext, requestedSessionId, errorMessage(error));
                return;
            }
            applyResult(handlerContext, session, requestedSessionId, result.messages(), result.pageInfo());
        }));
    }

    private static CompletableFuture<SessionHistoryLoadResult> supplyClaudePage(
            ClaudeSession session,
            String requestedSessionId,
            Integer beforeTurn
    ) {
        String cwd = session.getCwd();
        return CompletableFuture.supplyAsync(
                // 低频用户操作,按需构造(ClaudeHistoryService 构造拉起 NodeService,不做字段级缓存)。
                () -> new ClaudeHistoryService().loadHistoryPage(requestedSessionId, cwd, beforeTurn),
                AppExecutorUtil.getAppExecutorService()
        );
    }

    private static void applyResult(
            HandlerContext handlerContext,
            ClaudeSession session,
            String requestedSessionId,
            List<JsonObject> messages,
            JsonObject pageInfo
    ) {
        // applyCodexHistoryPage 实现是 provider 无关的(parseServerMessage + prependMessages + 全量 notify)。
        session.applyCodexHistoryPage(messages, CodexHistoryPageMode.PREPEND);
        if (!isCurrentSession(handlerContext, session, requestedSessionId)) {
            return;
        }
        handlerContext.dispatchEvent(
                DownstreamEvent.HISTORY_CODEX_PAGE_INFO.value(),
                handlerContext.escapeJs(GsonHolder.GSON.toJson(pageInfo))
        );
    }

    static boolean isCurrentSession(HandlerContext context, ClaudeSession expectedSession, String sessionId) {
        if (expectedSession == null
                || context.getSession() != expectedSession
                || !sessionId.equals(expectedSession.getSessionId())) {
            return false;
        }
        String sessionProvider = expectedSession.getProvider();
        String currentProvider = context.getCurrentProvider();
        return (ProviderType.CODEX.value().equals(sessionProvider) && ProviderType.CODEX.value().equals(currentProvider))
                || (ProviderType.CLAUDE.value().equals(sessionProvider) && ProviderType.CLAUDE.value().equals(currentProvider));
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
