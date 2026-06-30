package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class CodexHistoryProviderAdapter implements HistoryProviderAdapter {
    private static final Logger LOG = Logger.getInstance(CodexHistoryProviderAdapter.class);

    private final HandlerContext context;

    CodexHistoryProviderAdapter(HandlerContext context) {
        this.context = context;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return new CodexHistoryReader().getSessionsForProjectAsJson(projectPath);
    }

    @Override
    public List<JsonObject> loadMessages(String sessionId, String projectPath) {
        return context.getCodexSDKBridge() != null
                ? context.getCodexSDKBridge().getSessionMessages(sessionId, projectPath)
                : List.of();
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
        String homeDir = PlatformUtils.getHomeDirectory();
        Path sessionDir = Paths.get(homeDir, ".codex", "sessions");

        if (!Files.exists(sessionDir)) {
            LOG.error("[HistoryHandler] Codex session directory not found: " + sessionDir);
            return HistoryDeleteResult.none();
        }

        boolean deleted = false;
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            List<Path> sessionFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isCodexSessionFileMatch(path, sessionId))
                    .collect(Collectors.toList());

            for (Path sessionFile : sessionFiles) {
                try {
                    Files.delete(sessionFile);
                    LOG.info("[HistoryHandler] Deleted Codex session file: " + sessionFile);
                    deleted = true;
                } catch (Exception e) {
                    LOG.error("[HistoryHandler] Failed to delete Codex session file: "
                            + sessionFile + " - " + e.getMessage(), e);
                }
            }
        }
        return new HistoryDeleteResult(deleted, 0);
    }

    @Override
    public void clearCache(String projectPath) {
        SessionIndexCache.getInstance().clearAllCodexCache();
        SessionIndexManager.getInstance().clearAllCodexIndex();
    }

    static boolean isCodexSessionFileMatch(Path path, String sessionId) {
        if (path == null || sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return fileName.endsWith("-" + sessionId + ".jsonl");
    }
}
