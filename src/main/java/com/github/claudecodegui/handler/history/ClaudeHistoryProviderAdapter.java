package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PathUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
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
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.DELETE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return new ClaudeHistoryReader().getProjectDataAsJson(projectPath);
    }

    @Override
    public HistoryMessageBatch loadMessages(
            String sessionId,
            String projectPath,
            HistoryMessageReadPolicy policy
    ) {
        Path sessionFile = resolveSessionFile(projectPath, sessionId);
        return sessionFile == null ? HistoryMessageBatch.empty() : loadMessagesFromFile(sessionFile, policy);
    }

    static HistoryMessageBatch loadMessagesFromFile(Path sessionFile, HistoryMessageReadPolicy policy) {
        BoundedHistoryMessageCollector collector = new BoundedHistoryMessageCollector(policy);
        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ClaudeHistoryReader.ConversationMessage message = GsonHolder.GSON.fromJson(
                            line,
                            ClaudeHistoryReader.ConversationMessage.class
                    );
                    if (message != null) {
                        collector.append(GsonHolder.GSON.toJsonTree(message).getAsJsonObject());
                    }
                } catch (Exception e) {
                    LOG.debug("[HistoryHandler] Failed to parse Claude history message: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to read Claude history messages: " + e.getMessage(), e);
            return HistoryMessageBatch.empty();
        }
        return collector.toBatch();
    }

    private static Path resolveSessionFile(String projectPath, String sessionId) {
        if (projectPath == null || projectPath.isBlank() || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path projectsDir = Paths.get(PlatformUtils.getHomeDirectory(), ".claude", "projects");
        Path projectDir = projectsDir.resolve(PathUtils.sanitizePath(projectPath)).normalize();
        Path sessionFile = projectDir.resolve(sessionId + ".jsonl").normalize();
        if (!sessionFile.startsWith(projectDir) || !Files.isRegularFile(sessionFile)) {
            return null;
        }
        return sessionFile;
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
