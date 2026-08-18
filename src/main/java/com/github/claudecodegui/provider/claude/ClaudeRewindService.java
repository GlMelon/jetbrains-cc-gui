package com.github.claudecodegui.provider.claude;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Claude CLI file checkpoint restoration facade.
 */
public class ClaudeRewindService {

    private static final Logger LOG = Logger.getInstance(ClaudeRewindService.class);

    private final ClaudeRewindQueryService rewindQueryService;

    public ClaudeRewindService() {
        this(new ClaudeRewindQueryService(
                LOG,
                () -> ClaudeCliDetector.getInstance().findCliExecutable()
        ));
    }

    ClaudeRewindService(ClaudeRewindQueryService rewindQueryService) {
        this.rewindQueryService = rewindQueryService;
    }

    public CompletableFuture<JsonObject> rewindFiles(String sessionId, String userMessageId, String cwd) {
        return rewindQueryService.rewindFiles(sessionId, userMessageId, cwd);
    }
}
