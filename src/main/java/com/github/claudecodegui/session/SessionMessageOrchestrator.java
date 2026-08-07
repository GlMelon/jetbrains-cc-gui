package com.github.claudecodegui.session;

import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Owns session-history loading and post-send message reconciliation.
 */
public class SessionMessageOrchestrator {

    private static final Logger LOG = Logger.getInstance(SessionMessageOrchestrator.class);
    private static final int MAX_UUID_SYNC_RETRIES = 3;

    public interface SessionHistoryAccess {
        List<JsonObject> getProviderSessionMessages(String provider, String sessionId, String cwd);

        default SessionHistoryLoadResult getProviderInitialSessionHistory(
                String provider, String sessionId, String cwd) {
            return SessionHistoryLoadResult.fromMessages(getProviderSessionMessages(provider, sessionId, cwd));
        }

        JsonObject getLatestClaudeUserMessage(String sessionId, String cwd);
    }

    @FunctionalInterface
    public interface UsageDisplay {
        void show(int usedTokens, int maxTokens);
    }

    private final SessionState state;
    private final MessageParser messageParser;
    private final SessionCallbackFacade callbackFacade;
    private final SessionHistoryAccess historyAccess;
    private final UsageDisplay usageDisplay;
    private final long initialUuidSyncDelayMs;
    private final long uuidRetryDelayMs;

    public SessionMessageOrchestrator(
            Project project,
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess
    ) {
        this(
                state,
                messageParser,
                callbackFacade,
                historyAccess,
                (usedTokens, maxTokens) -> ClaudeNotifier.setTokenUsage(project, usedTokens, maxTokens),
                100,
                50
        );
    }

    SessionMessageOrchestrator(
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess,
            UsageDisplay usageDisplay,
            long initialUuidSyncDelayMs,
            long uuidRetryDelayMs
    ) {
        this.state = state;
        this.messageParser = messageParser;
        this.callbackFacade = callbackFacade;
        this.historyAccess = historyAccess;
        this.usageDisplay = usageDisplay;
        this.initialUuidSyncDelayMs = initialUuidSyncDelayMs;
        this.uuidRetryDelayMs = uuidRetryDelayMs;
    }

    public CompletableFuture<Void> syncUserMessageUuidsAfterSend() {
        // codex 跳过 UUID 同步:经 ProviderType SSOT 精确判等(总则五·开闭 / E6),消除裸 "codex" 字面量。
        if (ProviderType.CODEX.value().equals(state.getProvider()) || findLatestUnresolvedUserMessage() == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            sleep(initialUuidSyncDelayMs);
            updateUserMessageUuids();
        });
    }

    void updateUserMessageUuids() {
        String sessionId = state.getSessionId();
        String cwd = state.getCwd();

        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        if (findLatestUnresolvedUserMessage() == null) {
            return;
        }

        for (int attempt = 1; attempt <= MAX_UUID_SYNC_RETRIES; attempt++) {
            try {
                JsonObject latestClaudeUserMessage = historyAccess.getLatestClaudeUserMessage(sessionId, cwd);
                if (latestClaudeUserMessage == null) {
                    if (attempt < MAX_UUID_SYNC_RETRIES) {
                        sleep(uuidRetryDelayMs);
                        continue;
                    }
                    return;
                }

                ClaudeSession.Message matchedMessage = patchMatchingUserMessage(latestClaudeUserMessage);
                if (matchedMessage != null && matchedMessage.raw != null && matchedMessage.raw.has("uuid")) {
                    callbackFacade.notifyUserMessageUuidPatched(
                            matchedMessage.content != null ? matchedMessage.content : "",
                            matchedMessage.raw.get("uuid").getAsString()
                    );
                    return;
                }

                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            } catch (Exception e) {
                LOG.warn("[Rewind] Failed to update user message UUIDs (attempt " + attempt + "): " + e.getMessage());
                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            }
        }
    }

    public CompletableFuture<Void> loadFromServer() {
        if (state.getSessionId() == null) {
            return CompletableFuture.completedFuture(null);
        }

        state.setLoading(true);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());

        return CompletableFuture.runAsync(() -> {
            try {
                String currentSessionId = state.getSessionId();
                String currentCwd = state.getCwd();
                String currentProvider = state.getProvider();

                LOG.info("Loading session from server: sessionId=" + currentSessionId + ", cwd=" + currentCwd);
                SessionHistoryLoadResult historyResult =
                        historyAccess.getProviderInitialSessionHistory(currentProvider, currentSessionId, currentCwd);
                if (historyResult == null) {
                    historyResult = SessionHistoryLoadResult.fromMessages(List.of());
                }
                List<JsonObject> serverMessages = historyResult.messages();

                LOG.debug("Received " + serverMessages.size() + " messages from server");

                state.clearMessages();
                for (JsonObject msg : serverMessages) {
                    ClaudeSession.Message message = messageParser.parseServerMessage(msg);
                    if (message != null) {
                        state.addMessage(message);
                    }
                }

                restoreTokenUsage(serverMessages);
                callbackFacade.notifyMessageUpdate(state.getMessages());
                if (historyResult.pageInfo() != null) {
                    callbackFacade.notifyProtocolEvent(
                            DownstreamEvent.HISTORY_CODEX_PAGE_INFO.value(),
                            GsonHolder.GSON.toJson(historyResult.pageInfo())
                    );
                }
            } catch (Exception e) {
                state.setError(e.getMessage());
                LOG.error("Error loading session: " + e.getMessage(), e);
            } finally {
                state.setLoading(false);
                callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
            }
        });
    }

    private ClaudeSession.Message patchMatchingUserMessage(JsonObject historyMessage) {
        if (!historyMessage.has("type") || !"user".equals(historyMessage.get("type").getAsString())) {
            return null;
        }
        if (!historyMessage.has("uuid") || historyMessage.get("uuid").isJsonNull()) {
            return null;
        }

        String historyContent = extractMessageContentForMatching(historyMessage);
        if (historyContent == null || historyContent.isEmpty()) {
            return null;
        }

        String uuid = historyMessage.get("uuid").getAsString();
        List<ClaudeSession.Message> localMessages = state.getMessagesReference();
        for (int i = localMessages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message localMsg = localMessages.get(i);
            if (localMsg.type != ClaudeSession.Message.Type.USER) {
                continue;
            }
            synchronized (localMsg) {
                if (localMsg.raw != null && localMsg.raw.has("uuid") && !localMsg.raw.get("uuid").isJsonNull()) {
                    continue;
                }
                if (!historyContent.equals(localMsg.content)) {
                    continue;
                }

                if (localMsg.raw == null) {
                    localMsg.raw = createDefaultUserRaw(localMsg.content);
                }
                localMsg.raw.addProperty("uuid", uuid);
            }
            return localMsg;
        }

        return null;
    }

    static JsonObject createDefaultUserRaw(String content) {
        JsonObject raw = new JsonObject();
        JsonObject message = new JsonObject();
        JsonArray contentArray = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", content != null ? content : "");
        contentArray.add(textBlock);
        message.add("content", contentArray);
        raw.add("message", message);
        return raw;
    }

    private ClaudeSession.Message findLatestUnresolvedUserMessage() {
        List<ClaudeSession.Message> messages = state.getMessagesReference();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message message = messages.get(i);
            if (message.type != ClaudeSession.Message.Type.USER) {
                continue;
            }
            if (message.content == null || message.content.isEmpty() || "[tool_result]".equals(message.content)) {
                continue;
            }
            if (message.raw == null) {
                return message;
            }
            if (!message.raw.has("uuid") || message.raw.get("uuid").isJsonNull()) {
                return message;
            }
        }
        return null;
    }

    String extractMessageContentForMatching(JsonObject msg) {
        if (!msg.has("message") || !msg.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content")) {
            return null;
        }

        JsonElement contentElement = message.get("content");
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        if (contentElement.isJsonArray()) {
            JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contentArray.size(); i++) {
                JsonElement element = contentArray.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(block.get("text").getAsString());
                }
            }
            return sb.toString();
        }

        return null;
    }

    private void restoreTokenUsage(List<JsonObject> serverMessages) {
        try {
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromRawMessages(serverMessages, state.getProvider());
            if (lastUsage == null) {
                return;
            }

            int usedTokens = TokenUsageUtils.extractUsedTokens(lastUsage, state.getProvider());
            // 优先采用 provider 上报的真实上下文窗口(Codex model_context_window);静态模型映射仅作 fallback 分母。
            int fallbackMaxTokens = state.getEffectiveMaxTokens();
            int maxTokens = TokenUsageUtils.extractMaxTokens(lastUsage, fallbackMaxTokens);
            usageDisplay.show(usedTokens, maxTokens);
            LOG.debug("Restored token usage from history: " + usedTokens + " / " + maxTokens);
        } catch (Exception e) {
            LOG.warn("Failed to extract token usage from history: " + e.getMessage());
        }
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
