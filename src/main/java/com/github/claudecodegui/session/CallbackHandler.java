package com.github.claudecodegui.session;

import java.util.List;

/**
 * Callback handler.
 * Dispatches various session callback notifications.
 */
public class CallbackHandler {
    private ClaudeSession.SessionCallback callback;

    public void setCallback(ClaudeSession.SessionCallback callback) {
        this.callback = callback;
    }

    /**
     * Notify of a message update.
     */
    public void notifyMessageUpdate(List<ClaudeSession.Message> messages) {
        if (callback != null) {
            callback.onMessageUpdate(messages);
        }
    }

    /**
     * Notify of a state change.
     */
    public void notifyStateChange(boolean busy, boolean loading, String error) {
        if (callback != null) {
            callback.onStateChange(busy, loading, error);
        }
    }

    /**
     * Notify status message (e.g., reconnecting notices).
     */
    public void notifyStatusMessage(String message) {
        if (callback != null) {
            callback.onStatusMessage(message);
        }
    }

    /**
     * Notify that a session ID was received.
     */
    public void notifySessionIdReceived(String sessionId) {
        if (callback != null) {
            callback.onSessionIdReceived(sessionId);
        }
    }

    /**
     * Notify of a thinking status change.
     */
    public void notifyThinkingStatusChanged(boolean isThinking) {
        if (callback != null) {
            callback.onThinkingStatusChanged(isThinking);
        }
    }

    /**
     * Notify that slash commands were received.
     */
    public void notifySlashCommandsReceived(List<String> slashCommands) {
        if (callback != null) {
            callback.onSlashCommandsReceived(slashCommands);
        }
    }

    /**
     * Notify of a Node.js log (forwarded to frontend console).
     */
    public void notifyNodeLog(String log) {
        if (callback != null) {
            callback.onNodeLog(log);
        }
    }

    public void notifySummaryReceived(String summary) {
        if (callback != null) {
            callback.onSummaryReceived(summary);
        }
    }
    // ===== Streaming notification methods =====

    /**
     * Notify that streaming has started.
     */
    public void notifyStreamStart() {
        if (callback != null) {
            callback.onStreamStart();
        }
    }

    /**
     * Notify that the assistant response status phase changed.
     */
    public void notifyResponsePhase(AssistantResponseStatusPayload payload) {
        if (callback != null) {
            callback.onResponsePhase(payload);
        }
    }

    /**
     * Notify that streaming has ended.
     */
    public void notifyStreamEnd() {
        if (callback != null) {
            callback.onStreamEnd();
        }
    }

    /**
     * Notify that the provider completed a streaming turn, without waiting for frontend flush.
     */
    public void notifyStreamCompleted() {
        if (callback != null) {
            callback.onStreamCompleted();
        }
    }

    /**
     * Notify of a content delta (handled by the existing onContentDelta callback).
     */
    public void notifyContentDelta(String delta) {
        if (callback != null) {
            callback.onContentDelta(delta);
        }
    }

    /**
     * Notify of a thinking delta.
     */
    public void notifyThinkingDelta(String delta) {
        if (callback != null) {
            callback.onThinkingDelta(delta);
        }
    }

    /**
     * Notify that a block reset signal was received.
     * Frontend should clear streaming content refs to prevent cross-turn merging.
     */
    public void notifyBlockReset() {
        if (callback != null) {
            callback.onBlockReset();
        }
    }

    /**
     * Notify of a usage update.
     */
    public void notifyUsageUpdate(int usedTokens, int maxTokens) {
        if (callback != null) {
            callback.onUsageUpdate(usedTokens, maxTokens);
        }
    }

    /**
     * Notify of a usage update with provider-native token breakdown fields.
     */
    public void notifyUsageUpdate(String usageJson) {
        if (callback != null) {
            callback.onUsageUpdate(usageJson);
        }
    }

    /**
     * Notify that a specific message received its provider UUID.
     */
    public void notifyUserMessageUuidPatched(String content, String uuid) {
        if (callback != null) {
            callback.onUserMessageUuidPatched(content, uuid);
        }
    }

    /**
     * Variant carrying rewind availability (CLI-mode turns never reload history).
     */
    public void notifyUserMessageUuidPatched(String content, String uuid, boolean rewindable) {
        if (callback != null) {
            callback.onUserMessageUuidPatched(content, uuid, rewindable);
        }
    }

    public void notifyQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState state, int aheadCount) {
        if (callback != null) {
            callback.onQueueDisplayStateChanged(state, aheadCount);
        }
    }
    public void notifyProtocolEvent(String type, String payloadJson) {
        if (callback != null) {
            callback.onProtocolEvent(type, payloadJson);
        }
    }
}

