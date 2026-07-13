package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service to convert SDK-created sessions to CLI-recognizable sessions.
 *
 * @author Gadfly
 */
class SessionConversionService {

    private static final Logger LOG = Logger.getInstance(SessionConversionService.class);
    private static final Set<SessionEntrypoint> MANUAL_CONVERSION_SOURCES = Set.of(
            SessionEntrypoint.SDK_CLI,
            SessionEntrypoint.CLAUDE_VSCODE
    );

    private final HandlerContext context;
    private final Gson gson = new Gson();
    private final ClaudeSessionEntrypointRewriter entrypointRewriter;

    SessionConversionService(HandlerContext context) {
        this(context, new ClaudeSessionEntrypointRewriter());
    }

    SessionConversionService(
            HandlerContext context,
            ClaudeSessionEntrypointRewriter entrypointRewriter
    ) {
        this.context = context;
        this.entrypointRewriter = entrypointRewriter;
    }

    /**
     * Convert a non-CLI session to a CLI-recognizable session.
     *
     * @param sessionId session ID to convert
     * @param projectPath project path, or null to scan all projects
     */
    void convertSdkSession(String sessionId, String projectPath) {
        if (!HistoryDeleteService.isValidSessionId(sessionId)) {
            LOG.warn("[SessionConversionService] Conversion rejected: invalid sessionId");
            this.sendConversionResult(false, ConversionResultCode.INVALID_SESSION_ID);
            return;
        }

        if (this.isSessionActive(sessionId)) {
            LOG.warn("[SessionConversionService] Conversion rejected: session is active");
            this.sendConversionResult(false, ConversionResultCode.SESSION_ACTIVE);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ClaudeSessionEntrypointRewriter.RewriteResult result = this.entrypointRewriter.rewrite(
                    sessionId,
                    projectPath,
                    MANUAL_CONVERSION_SOURCES,
                    SessionEntrypoint.CLI
            );
            this.handleRewriteResult(sessionId, result);
        });
    }

    private void handleRewriteResult(
            String sessionId,
            ClaudeSessionEntrypointRewriter.RewriteResult result
    ) {
        switch (result.status()) {
            case REWRITTEN -> {
                LOG.debug("[SessionConversionService] Successfully converted session: " + sessionId
                        + " (" + result.modifiedCount() + " lines modified)");
                this.sendConversionResult(true, null);
            }
            case ALREADY_TARGET -> this.sendConversionResult(true, ConversionResultCode.ALREADY_CLI_SESSION);
            case INVALID_SESSION_ID -> this.sendConversionResult(false, ConversionResultCode.INVALID_SESSION_ID);
            case SESSION_NOT_FOUND -> this.sendConversionResult(false, ConversionResultCode.SESSION_NOT_FOUND);
            case FILE_NOT_EXIST -> this.sendConversionResult(false, ConversionResultCode.FILE_NOT_EXIST);
            case FILE_LOCKED -> this.sendConversionResult(false, ConversionResultCode.FILE_LOCKED);
            case SOURCE_NOT_ACCEPTED -> this.sendConversionResult(false, ConversionResultCode.NOT_SDK_SESSION);
            case FAILED -> this.sendConversionResult(false, ConversionResultCode.CONVERSION_FAILED);
        }
    }

    // Package-private so SessionConversionServiceTest can exercise the per-row rewrite directly.
    String convertEntrypointInLine(
            String line,
            AtomicBoolean hasCliEntrypoint,
            AtomicInteger modifiedCount
    ) {
        ClaudeSessionEntrypointRewriter.LineRewrite rewrite = this.entrypointRewriter.rewriteLine(
                line,
                MANUAL_CONVERSION_SOURCES,
                SessionEntrypoint.CLI
        );
        if (rewrite.modified()) {
            modifiedCount.incrementAndGet();
        }
        if (rewrite.hasTargetEntrypoint()) {
            hasCliEntrypoint.set(true);
        }
        return rewrite.line();
    }

    private boolean isSessionActive(String sessionId) {
        try {
            var session = this.context.getSession();
            return session != null && sessionId.equals(session.getSessionId());
        } catch (Exception e) {
            LOG.warn("[SessionConversionService] Failed to check active session: " + e.getMessage());
            return false;
        }
    }

    private void sendConversionResult(boolean success, ConversionResultCode code) {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        if (code != null) {
            result.addProperty(success ? "infoCode" : "errorCode", code.getCode());
        }

        Project project = this.context.getProject();
        if (project != null && !project.isDisposed()) {
            String escapedJson = this.context.escapeJs(this.gson.toJson(result));
            String jsCode = "if (window.onConversionResult) { window.onConversionResult('" + escapedJson + "'); }";
            this.context.executeJavaScriptOnEDT(jsCode);
        }
    }
}
