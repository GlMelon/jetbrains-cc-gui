package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilities for validating and parsing history-delete requests.
 */
class HistoryDeleteService {

    private static final Logger LOG = Logger.getInstance(HistoryDeleteService.class);
    private static final Gson GSON = GsonHolder.GSON;

    // Reject anything outside [A-Za-z0-9._-] to defeat path-traversal payloads such as "../foo".
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final HandlerContext context;
    private final HistoryLoadService historyLoadService;

    HistoryDeleteService(HandlerContext context,
                         NodeJsServiceCaller nodeJsServiceCaller,
                         HistoryLoadService historyLoadService) {
        this.context = context;
        this.historyLoadService = historyLoadService;
    }

    void handleDeleteSession(String sessionId, String currentProvider) {
        if (!isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return;
        }
        quiesceActiveSessionForDeletion(
                context == null ? null : context.getSession(),
                Collections.singleton(sessionId),
                currentProvider
        ).exceptionally(error -> {
            LOG.warn("[HistoryHandler] Failed to stop active session before deletion: "
                    + error.getMessage(), error);
            if (historyLoadService != null) {
                historyLoadService.handleLoadHistoryData(currentProvider);
            }
            return null;
        });
    }

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    static List<String> parseSessionIds(String content) {
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonArray()) {
                collectSessionIds(parsed.getAsJsonArray(), sessionIds);
            } else if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                JsonElement sessionIdsElement = object.get("sessionIds");
                if (sessionIdsElement != null && sessionIdsElement.isJsonArray()) {
                    collectSessionIds(sessionIdsElement.getAsJsonArray(), sessionIds);
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Batch delete sessionIds parse failed: " + e.getMessage());
        }

        return new ArrayList<>(sessionIds);
    }

    private static void collectSessionIds(JsonArray array, LinkedHashSet<String> sessionIds) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }

            String sessionId = element.getAsString().trim();
            if (sessionId.isEmpty()) {
                continue;
            }
            if (!isValidSessionId(sessionId)) {
                LOG.warn("[HistoryHandler] Batch delete ignored invalid sessionId");
                continue;
            }
            sessionIds.add(sessionId);
        }
    }

    static boolean isCodexSessionDeletionComplete(
            Collection<Path> matchedPaths,
            Collection<Path> failedPaths
    ) {
        return matchedPaths != null
                && !matchedPaths.isEmpty()
                && Collections.disjoint(matchedPaths,
                failedPaths == null ? Collections.emptySet() : failedPaths);
    }

    static List<Path> findCodexSessionFiles(Path sessionDir, String sessionId) throws java.io.IOException {
        Map<String, List<Path>> matches = findCodexSessionFiles(
                sessionDir, Collections.singleton(sessionId));
        return matches.getOrDefault(sessionId, Collections.emptyList());
    }

    static Map<String, List<Path>> findCodexSessionFiles(
            Path sessionDir,
            Collection<String> sessionIds
    ) throws java.io.IOException {
        List<CodexSessionFile> candidates;
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            candidates = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .map(path -> new CodexSessionFile(path, readCodexSessionLink(path)))
                    .collect(Collectors.toList());
        }

        Map<String, List<Path>> matches = new LinkedHashMap<>();
        if (sessionIds == null) {
            return matches;
        }
        for (String sessionId : sessionIds) {
            if (sessionId != null && !matches.containsKey(sessionId)) {
                matches.put(sessionId, findCodexSessionFiles(candidates, sessionId));
            }
        }
        return matches;
    }

    private static List<Path> findCodexSessionFiles(
            List<CodexSessionFile> candidates,
            String sessionId
    ) {
        LinkedHashSet<String> matchedSessionIds = new LinkedHashSet<>();
        matchedSessionIds.add(sessionId);
        LinkedHashSet<Path> matchedFiles = new LinkedHashSet<>();

        boolean changed;
        do {
            changed = false;
            for (CodexSessionFile candidate : candidates) {
                if (matchedFiles.contains(candidate.path)) {
                    continue;
                }
                CodexSessionLink link = candidate.link;
                boolean directFileMatch = isCodexSessionFileMatch(candidate.path, sessionId);
                boolean sessionMatch = link.sessionId != null && matchedSessionIds.contains(link.sessionId);
                boolean parentMatch = link.parentThreadId != null
                        && matchedSessionIds.contains(link.parentThreadId);
                if (!directFileMatch && !sessionMatch && !parentMatch) {
                    continue;
                }
                matchedFiles.add(candidate.path);
                changed = true;
                if ((sessionMatch || parentMatch) && link.sessionId != null) {
                    matchedSessionIds.add(link.sessionId);
                }
            }
        } while (changed);
        return new ArrayList<>(matchedFiles);
    }

    private static CodexSessionLink readCodexSessionLink(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isEmpty()) {
                return CodexSessionLink.EMPTY;
            }
            JsonElement parsed = JsonParser.parseString(firstLine);
            if (!parsed.isJsonObject()) {
                return CodexSessionLink.EMPTY;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!"session_meta".equals(getJsonString(root, "type"))) {
                return CodexSessionLink.EMPTY;
            }
            JsonObject payload = getJsonObject(root, "payload");
            String rolloutSessionId = getJsonString(payload, "id");
            JsonObject source = getJsonObject(payload, "source");
            JsonObject subagent = getJsonObject(source, "subagent");
            JsonObject threadSpawn = getJsonObject(subagent, "thread_spawn");
            String parentThreadId = getJsonString(threadSpawn, "parent_thread_id");
            return new CodexSessionLink(rolloutSessionId, parentThreadId);
        } catch (Exception e) {
            LOG.debug("[HistoryHandler] Failed to parse Codex session metadata: "
                    + path.getFileName(), e);
            return CodexSessionLink.EMPTY;
        }
    }

    private static JsonObject getJsonObject(JsonObject parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String getJsonString(JsonObject parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }

    static CompletableFuture<Void> quiesceActiveSessionForDeletion(
            ClaudeSession session,
            Collection<String> sessionIds,
            String currentProvider
    ) {
        if (session == null || sessionIds == null || sessionIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String activeSessionId = session.getSessionId();
        if (activeSessionId == null
                || !sessionIds.contains(activeSessionId)
                || !Objects.equals(session.getProvider(), currentProvider)) {
            return CompletableFuture.completedFuture(null);
        }
        return session.interrupt();
    }

    static boolean isCodexSessionFileMatch(Path path, String sessionId) {
        return CodexHistoryProviderAdapter.isCodexSessionFileMatch(path, sessionId);
    }

    static String sessionIdsToJson(List<String> sessionIds) {
        return GSON.toJson(sessionIds);
    }

    private record CodexSessionFile(Path path, CodexSessionLink link) {}

    private record CodexSessionLink(String sessionId, String parentThreadId) {
        private static final CodexSessionLink EMPTY = new CodexSessionLink(null, null);
    }
}
