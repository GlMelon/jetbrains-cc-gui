package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PathUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ClaudeHistoryProviderAdapter implements HistoryProviderAdapter {
    private static final Logger LOG = Logger.getInstance(ClaudeHistoryProviderAdapter.class);

    ClaudeHistoryProviderAdapter() {
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CLAUDE;
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return new ClaudeHistoryReader().getProjectDataAsJson(projectPath);
    }

    @Override
    public List<JsonObject> loadMessages(String sessionId, String projectPath) {
        String messagesJson = new ClaudeHistoryReader().getSessionMessagesAsJson(projectPath, sessionId);
        var parsed = JsonParser.parseString(messagesJson);
        if (!parsed.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> messages = new ArrayList<>();
        for (var element : parsed.getAsJsonArray()) {
            if (element.isJsonObject()) {
                messages.add(element.getAsJsonObject());
            }
        }
        return messages;
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null, cannot delete Claude session");
            return HistoryDeleteResult.none();
        }

        String homeDir = PlatformUtils.getHomeDirectory();
        Path claudeDir = Paths.get(homeDir, ".claude");
        Path projectsDir = claudeDir.resolve("projects");
        String sanitizedPath = PathUtils.sanitizePath(projectPath);
        Path sessionDir = projectsDir.resolve(sanitizedPath);

        if (!Files.exists(sessionDir)) {
            LOG.error("[HistoryHandler] Claude project directory not found: " + sessionDir);
            return HistoryDeleteResult.none();
        }

        boolean mainDeleted = false;
        int agentFilesDeleted = 0;

        Path mainSessionFile = sessionDir.resolve(sessionId + ".jsonl").normalize();
        if (!mainSessionFile.startsWith(sessionDir.normalize())) {
            LOG.warn("[HistoryHandler] Refused out-of-bounds path: " + mainSessionFile);
            return HistoryDeleteResult.none();
        }
        if (Files.exists(mainSessionFile)) {
            Files.delete(mainSessionFile);
            LOG.info("[HistoryHandler] Deleted main session file: " + mainSessionFile.getFileName());
            mainDeleted = true;
        } else {
            LOG.warn("[HistoryHandler] Main session file not found: " + mainSessionFile.getFileName());
        }

        try (Stream<Path> stream = Files.list(sessionDir)) {
            List<Path> agentFiles = stream
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith("agent-") && filename.endsWith(".jsonl")
                                && isAgentFileRelatedToSession(path, sessionId);
                    })
                    .collect(Collectors.toList());

            for (Path agentFile : agentFiles) {
                try {
                    Files.delete(agentFile);
                    LOG.info("[HistoryHandler] Deleted related agent file: " + agentFile.getFileName());
                    agentFilesDeleted++;
                } catch (Exception e) {
                    LOG.error("[HistoryHandler] Failed to delete agent file: "
                            + agentFile.getFileName() + " - " + e.getMessage(), e);
                }
            }
        }

        return new HistoryDeleteResult(mainDeleted, agentFilesDeleted);
    }

    @Override
    public void clearCache(String projectPath) {
        if (projectPath == null) {
            return;
        }
        SessionIndexCache.getInstance().clearProject(projectPath);
        SessionIndexManager.getInstance().clearProjectIndex(CommonConstants.PROVIDER_CLAUDE, projectPath);
    }

    private boolean isAgentFileRelatedToSession(Path agentFilePath, String sessionId) {
        try (BufferedReader reader = Files.newBufferedReader(agentFilePath, StandardCharsets.UTF_8)) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 20) {
                if (line.contains("\"sessionId\":\"" + sessionId + "\"") ||
                            line.contains("\"parentSessionId\":\"" + sessionId + "\"")) {
                    LOG.debug("[HistoryHandler] Agent file " + agentFilePath.getFileName()
                            + " belongs to session " + sessionId);
                    return true;
                }
                lineCount++;
            }
            LOG.debug("[HistoryHandler] Agent file " + agentFilePath.getFileName()
                    + " does not belong to session " + sessionId);
            return false;
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to read agent file "
                    + agentFilePath.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
}
