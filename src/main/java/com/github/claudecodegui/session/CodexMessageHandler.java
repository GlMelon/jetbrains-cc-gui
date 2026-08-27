package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.CodexMessageConverter;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.session.ClaudeSession.Message;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.ClaudeHistoryWriter;
import com.github.claudecodegui.util.CodexHistoryWriter;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.github.claudecodegui.util.UsageCostCalculator;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Codex message callback handler.
 * Processes messages returned by Codex AI.
 * Similar to ClaudeMessageHandler but handles Codex's simpler message format,
 * primarily dealing with streaming text output.
 */
public class CodexMessageHandler implements MessageCallback {
    /**
     * log.
     */
    private static final Logger LOG = Logger.getInstance(CodexMessageHandler.class);

    /**
     * state.
     */
    private final SessionState state;
    /**
     * callback handler.
     */
    private final CallbackHandler callbackHandler;
    /**
     * expected runtime session epoch.
     */
    private final String expectedRuntimeSessionEpoch;
    /**
     * message merger.
     */
    private final MessageMerger messageMerger = new MessageMerger();

    /**
     * assistant content.
     */ // Content accumulator for the current assistant message
    private final StringBuilder assistantContent = new StringBuilder();

    /**
     * current assistant message.
     */ // Current assistant message object being processed
    private Message currentAssistantMessage = null;

    /**
     * Deduplicates streaming deltas that were already included via conservative full-message sync.
     * Mirrors the same mechanism used by ClaudeMessageHandler.
     */
    private final ReplayDeduplicator replayDedup = new ReplayDeduplicator();

    /**
     * is streaming.
     */
    private boolean isStreaming = false;
    /**
     * stream ended this turn.
     */
    private boolean streamEndedThisTurn = false;
    /**
     * Whether thinking is currently active.
     */
    private boolean isThinking = false;

    private com.google.gson.JsonObject currentTurnContextUsage;

    /**
     * 便捷构造器(不参与 epoch 守卫);测试与遗留路径用。
     *
     * @param state state
     * @param callbackHandler callback handler
     */
    public CodexMessageHandler(SessionState state, CallbackHandler callbackHandler) {
        this(state, callbackHandler, null);
    }

    /**
     * 主构造器。
     *
     * @param state state
     * @param callbackHandler callback handler
     * @param expectedRuntimeSessionEpoch 构造时绑定的运行时会话 epoch;运行时切换后,
     *                                     旧进程的回调会因 epoch 不匹配被丢弃(防串台)。
     *                                     null/空 表示不参与守卫。
     */
    public CodexMessageHandler(SessionState state, CallbackHandler callbackHandler, String expectedRuntimeSessionEpoch) {
        this.state = state;
        this.callbackHandler = callbackHandler;
        this.expectedRuntimeSessionEpoch = expectedRuntimeSessionEpoch;
    }

    /**
     * 判断当前回调是否来自已被替换的运行时会话(epoch 不匹配)。
     * 与 {@link ClaudeMessageHandler} 保持一致的过期回调丢弃机制。
     *
     * @return true 表示该回调属于旧运行时,应被忽略
     */
    private boolean isStaleRuntimeEpoch() {
        if (expectedRuntimeSessionEpoch == null || expectedRuntimeSessionEpoch.isEmpty()) {
            return false;
        }
        String currentEpoch = state.getRuntimeSessionEpoch();
        return currentEpoch != null && !expectedRuntimeSessionEpoch.equals(currentEpoch);
    }

    /**
     * Handle a received message by dispatching to the appropriate handler based on type.
     *
     * @param type type
     * @param content content
     */
    @Override
    public void onMessage(String type, String content) {
        if (isStaleRuntimeEpoch()) {
            LOG.debug("Ignoring stale Codex onMessage (runtime session switched): type=" + type);
            return;
        }
        // [FIX] Handle multiple message types
        // Codex message-service.js sends:
        // - type='assistant': contains thinking, tool_use, text
        // - type='user': contains tool_result
        LOG.debug("CodexMessageHandler.onMessage: type=" + type + ", content length=" + (content != null ? content.length() : 0));

        switch (type) {
            case CommonConstants.MSG_TYPE_ASSISTANT:
                handleAssistantMessage(content);
                break;
            case CommonConstants.MSG_TYPE_USER:
                handleUserMessage(content);
                break;
            case CommonConstants.MSG_TYPE_TOOL_USE:
                handleToolUse(content);
                break;
            case CommonConstants.MSG_TYPE_TOOL_RESULT:
                handleToolResult(content);
                break;
            case CliConstants.MSG_RESULT:
                handleResultMessage(content);
                break;
            case CliConstants.MSG_USAGE:
                handleUsageMessage(content);
                break;
            case CliConstants.MSG_SESSION_ID:
                handleSessionId(content);
                break;
            case CliConstants.CODEX_MSG_EVENT_MSG:
                handleEventMessage(content);
                break;
            case CliConstants.MSG_STREAM_START:
                handleStreamStart();
                break;
            case CommonConstants.MSG_TYPE_THINKING:
                handleThinkingMessage();
                break;
            case CliConstants.MSG_STREAM_END:
                handleStreamEnd();
                break;
            case CliConstants.MSG_BLOCK_RESET:
                handleBlockReset();
                break;
            case CliConstants.MSG_THINKING_DELTA:
                handleThinkingDelta(content);
                break;
            case CliConstants.MSG_CONTENT_DELTA:
            case CommonConstants.MSG_TYPE_TEXT:
                handleContentDelta(content);
                break;
            case CliConstants.CODEX_MSG_STATUS:
                if (content != null && !content.trim().isEmpty()) {
                    callbackHandler.notifyStatusMessage(content);
                }
                break;
            case CliConstants.MSG_MESSAGE_END:
                handleMessageEnd();
                break;
            case CliConstants.MSG_RESPONSE_PHASE:
                handleResponsePhase(content);
                break;
            case CliConstants.MSG_SESSION_TITLE:
                // kimi ACP 通道原生标题(session_info_update.title,见 KimiAcpCliSession.finalizeTurn)。
                // payload 形状 {sessionId, title} 与 CliSessionTitleService 下发的 SESSION_TITLE 一致,
                // 前端按 DownstreamEvent.SESSION_TITLE 渲染,零改动。
                callbackHandler.notifyProtocolEvent(
                        com.github.claudecodegui.protocol.DownstreamEvent.SESSION_TITLE.value(), content);
                break;
            default:
                LOG.debug("CodexMessageHandler: Unhandled message type: " + type);
                break;
        }
    }

    /**
     * Handle a response-phase status signal forwarded from the CLI send path
     * (MCP_SYNCING / CONNECTING) or the api_retry signal. Mirrors ClaudeMessageHandler:
     * reuses the onMessage channel so CLI sessions report real startup boundaries.
     */
    private void handleResponsePhase(String content) {
        String phaseValue = content != null ? content.trim() : "";
        if (phaseValue.isEmpty()) {
            return;
        }
        String provider = state.getProvider();
        if (phaseValue.equals(CliConstants.PHASE_API_RETRY)
                || phaseValue.startsWith(CliConstants.PHASE_API_RETRY + ":")) {
            // api_retry:phase 切 API_RETRY + 重试计数 description,镜像 ClaudeMessageHandler。
            callbackHandler.notifyResponsePhase(
                    AssistantResponseStatusPayload.forApiRetryFromContent(provider, 0L, phaseValue));
            return;
        }
        AssistantResponsePhase phase = AssistantResponsePhase.fromValue(phaseValue);
        if (phase == null) {
            return;
        }
        callbackHandler.notifyResponsePhase(
                AssistantResponseStatusPayload.forProvider(phase, provider, 0L));
    }

    /**
     * Handle an error from the CLI stream.
     *
     * @param error error
     */
    @Override
    public void onError(String error) {
        if (isStaleRuntimeEpoch()) {
            LOG.debug("Ignoring stale Codex onError (runtime session switched)");
            return;
        }
        isStreaming = false;
        streamEndedThisTurn = false;
        resetThinkingStatus();
        replayDedup.reset();
        state.setError(error);
        state.setBusy(false);
        state.setLoading(false);
        state.setQueueDisplayState(ClaudeSession.SessionCallback.QueueDisplayState.NONE);
        state.setQueueAheadCount(0);

        appendProviderErrorToAssistantMessage(error);
        persistProviderError(error);
        callbackHandler.notifyMessageUpdate(state.getMessages());
        // 无条件补发 stream_end(与 ClaudeMessageHandler.onError 同因):前端
        // streamingActive 在 RESPONSE_PHASE(active) 即置位,turn 若死在
        // stream_start 之前,wasStreaming=false 会悬挂前端流式 footer;
        // 前端 minimal/skip 幂等兜底使未开流时的多发无害。
        callbackHandler.notifyStreamEnd();
        // 项4:删除 notifyStreamEnd 之后冗余的第二次 notifyMessageUpdate(上方已通知,与 Claude onError 对称)。
        resetStreamingAccumulator();
        callbackHandler.notifyQueueDisplayStateChanged(state.getQueueDisplayState(), state.getQueueAheadCount());
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    private void appendProviderErrorToAssistantMessage(String error) {
        // 错误归因跟随 state.getProvider()(对称 pushUsageUpdate:628):CodexMessageHandler 被 Codex 与
        // OpenCode 复用(B4 设计,见 SessionSendService.sendToOpenCode),硬编码 CODEX 会让 OpenCode 错误块
        // 标注 provider=codex,误导用户。
        currentAssistantMessage = ProviderErrorMessageSupport.appendToAssistantMessage(
                state,
                currentAssistantMessage,
                state.getProvider(),
                error
        );
    }

    private void persistProviderError(String error) {
        String sessionId = state.getSessionId();
        if (sessionId == null || sessionId.isBlank() || error == null || error.isBlank()) {
            return;
        }
        // OpenCode 复用 CodexMessageHandler(B4),但历史体系独立:OpenCode 尚无历史 writer(B7 规划中,
        // 见 OpenCodeProviderAdapter 注释)。OpenCode 错误不应写入 CodexHistoryWriter(~/.codex/sessions)
        // 污染 Codex 历史;仅 Codex 错误持久化到 Codex 专属历史。
        if (CommonConstants.PROVIDER_CODEX.equals(state.getProvider())) {
            CodexHistoryWriter.appendProviderError(sessionId, ProviderErrorMessageSupport.summarize(error), error, null);
        }
    }

    /**
     * Handle completion of a response turn.
     *
     * @param result result
     */
    @Override
    public void onComplete(CliResult result) {
        if (isStaleRuntimeEpoch()) {
            LOG.debug("Ignoring stale Codex onComplete (runtime session switched)");
            return;
        }
        if (result != null && result.interrupted) {
            handleInterruptedCompletion(result);
            return;
        }
        boolean streamEndedBeforeComplete = streamEndedThisTurn;
        boolean wasStreaming = isStreaming;

        isStreaming = false;
        streamEndedThisTurn = false;
        state.setBusy(false);
        state.setLoading(false);
        state.setQueueDisplayState(ClaudeSession.SessionCallback.QueueDisplayState.COMPLETED);
        state.setQueueAheadCount(0);
        state.updateLastModifiedTime();

        if (wasStreaming && !streamEndedBeforeComplete) {
            LOG.warn("Codex onComplete called without prior stream_end; forcing stream cleanup");
            callbackHandler.notifyMessageUpdate(state.getMessages());
            callbackHandler.notifyStreamEnd();
        }

        resetStreamingAccumulator();
        callbackHandler.notifyQueueDisplayStateChanged(state.getQueueDisplayState(), state.getQueueAheadCount());
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    private void handleInterruptedCompletion(CliResult result) {
        boolean streamEndedBeforeComplete = streamEndedThisTurn;
        boolean wasStreaming = isStreaming;

        isStreaming = false;
        streamEndedThisTurn = false;
        resetThinkingStatus();
        state.setError(null);
        state.setBusy(false);
        state.setLoading(false);
        state.setQueueDisplayState(ClaudeSession.SessionCallback.QueueDisplayState.COMPLETED);
        state.setQueueAheadCount(0);
        state.updateLastModifiedTime();

        if (result.error != null && !result.error.isBlank()) {
            state.addMessage(new Message(Message.Type.ASSISTANT, result.error));
            // Persist the interruption message to JSONL so it appears in history
            String sessionId = state.getSessionId();
            String cwd = state.getCwd();
            if (sessionId != null && cwd != null) {
                ClaudeHistoryWriter.appendAssistantMessage(cwd, sessionId, result.error);
            }
        }

        callbackHandler.notifyMessageUpdate(state.getMessages());
        if (wasStreaming && !streamEndedBeforeComplete) {
            callbackHandler.notifyStreamEnd();
        }

        resetStreamingAccumulator();
        callbackHandler.notifyQueueDisplayStateChanged(state.getQueueDisplayState(), state.getQueueAheadCount());
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    @Override
    public void onQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState queueState, int aheadCount) {
        if (isStaleRuntimeEpoch()) {
            LOG.debug("Ignoring stale Codex onQueueDisplayStateChanged (runtime session switched)");
            return;
        }
        state.setQueueDisplayState(queueState);
        state.setQueueAheadCount(aheadCount);
        callbackHandler.notifyQueueDisplayStateChanged(state.getQueueDisplayState(), state.getQueueAheadCount());
    }

    // ===== Private methods =====

    /**
     * Handle a complete assistant message in JSON format.
     * Contains thinking, tool_use, text, and other content types.
     *
     * @param jsonContent json content
     */
    private void handleAssistantMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            // Apply v0.1.3-codex filtering logic
            Message parsed = parseServerMessage(msgJson, Message.Type.ASSISTANT);
            if (parsed == null) {
                LOG.debug("Codex assistant message filtered out");
                return;
            }

            // Capture previous state before merge for conservative sync
            String previousAssistantContent = assistantContent.toString();
            String previousThinkingContent = currentAssistantMessage != null && currentAssistantMessage.raw != null
                    ? ReplayDeduplicator.extractThinkingContent(currentAssistantMessage.raw) : "";

            if (currentAssistantMessage != null) {
                com.google.gson.JsonObject mergedRaw = messageMerger.mergeAssistantMessage(currentAssistantMessage.raw, parsed.raw);
                if (!isStreaming) {
                    assistantContent.setLength(0);
                    CliOutputLimits.appendBounded(
                            assistantContent,
                            parsed.content != null ? parsed.content : "",
                            CliOutputLimits.MAX_ASSISTANT_CHARS);
                    currentAssistantMessage.content = assistantContent.toString();
                }
                currentAssistantMessage.raw = mergedRaw;
            } else {
                currentAssistantMessage = parsed;
                if (isStreaming) {
                    currentAssistantMessage.content = assistantContent.toString();
                } else {
                    assistantContent.setLength(0);
                    CliOutputLimits.appendBounded(
                            assistantContent,
                            parsed.content != null ? parsed.content : "",
                            CliOutputLimits.MAX_ASSISTANT_CHARS);
                    currentAssistantMessage.content = assistantContent.toString();
                }
                state.addMessage(parsed);
            }

            // Conservative sync: during streaming, activate replay dedup so that
            // subsequent content_delta/thinking_delta events that duplicate the
            // assistant message text are consumed (mirrors ClaudeMessageHandler).
            if (isStreaming) {
                String streamingText = ReplayDeduplicator.extractTextContent(currentAssistantMessage.raw);
                if (streamingText.length() > previousAssistantContent.length()) {
                    String replayedVisibleText = previousAssistantContent;
                    replayDedup.beginContentReplay(
                            replayedVisibleText,
                            ReplayDeduplicator.replayOffset(0, replayDedup.contentOffset())
                    );
                }
                String mergedThinkingContent = ReplayDeduplicator.extractThinkingContent(currentAssistantMessage.raw);
                if (mergedThinkingContent.length() > previousThinkingContent.length()) {
                    String replayedVisibleThinking = previousThinkingContent;
                    replayDedup.beginThinkingReplay(
                            replayedVisibleThinking,
                            ReplayDeduplicator.replayOffset(0, replayDedup.thinkingOffset())
                    );
                }
            }

            // During streaming, only push full message updates for structural changes (tool_use blocks).
            // Pure text/thinking content is already rendered via content_delta/thinking_delta channels.
            boolean hasToolUse = rawHasToolUse(currentAssistantMessage.raw);
            boolean shouldNotifyMessageUpdate = !isStreaming || hasToolUse;
            if (shouldNotifyMessageUpdate) {
                callbackHandler.notifyMessageUpdate(state.getMessages());
            }

            LOG.debug("Codex assistant message synchronized with raw JSON");
        } catch (Exception e) {
            LOG.warn("Failed to parse assistant message: " + e.getMessage());
        }
    }

    /**
     * 检查 raw 消息中是否包含 tool_use 块。
     * 委托给 {@link RawMessageHelper#hasToolUse} 统一实现。
     *
     * @param raw 要检查的 raw JsonObject
     * @return 如果包含 tool_use 块返回 true
     */
    private boolean rawHasToolUse(com.google.gson.JsonObject raw) {
        return RawMessageHelper.hasToolUse(raw);
    }

    /**
     * Handle a user message (primarily tool_result).
     *
     * @param jsonContent json content
     */
    private void handleUserMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);

            // Apply v0.1.3-codex filtering logic
            Message parsed = parseServerMessage(msgJson, Message.Type.USER);
            if (parsed == null) {
                LOG.debug("Codex user message filtered out");
                return;
            }

            state.addMessage(parsed);
            callbackHandler.notifyMessageUpdate(state.getMessages());

            LOG.debug("Codex user message (tool_result) added");
        } catch (Exception e) {
            LOG.warn("Failed to parse user message: " + e.getMessage());
        }
    }

    /**
     * 处理直接的 tool_use 块事件，将其包装为 assistant 消息。
     * 使用 {@link RawMessageHelper#wrapAsAssistantRaw} 统一包装。
     *
     * @param jsonContent tool_use 块的 JSON 内容
     */
    private void handleToolUse(String jsonContent) {
        if (jsonContent == null || !jsonContent.startsWith("{")) {
            return;
        }

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject toolUseBlock = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            com.google.gson.JsonObject rawAssistant = RawMessageHelper.wrapAsAssistantRaw(toolUseBlock);
            handleAssistantMessage(rawAssistant.toString());
        } catch (Exception e) {
            LOG.warn("Failed to parse tool_use JSON: " + e.getMessage());
        }
    }

    /**
     * 处理直接的 tool_result 块事件，将其包装为 user 消息。
     * 使用 {@link RawMessageHelper#wrapAsUserRaw} 统一包装。
     *
     * @param jsonContent tool_result 块的 JSON 内容
     */
    private void handleToolResult(String jsonContent) {
        if (jsonContent == null || !jsonContent.startsWith("{")) {
            return;
        }

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject toolResultBlock = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            com.google.gson.JsonObject rawUser = RawMessageHelper.wrapAsUserRaw(toolResultBlock);
            handleUserMessage(rawUser.toString());
        } catch (Exception e) {
            LOG.warn("Failed to parse tool_result JSON: " + e.getMessage());
        }
    }

    /**
     * Handle the session_id (Codex thread ID) for session recovery.
     *
     * @param threadId thread id
     */
    private void handleSessionId(String threadId) {
        if (threadId != null && !threadId.trim().isEmpty()) {
            String currentSessionId = state.getSessionId();
            if (currentSessionId != null && !currentSessionId.equals(threadId)) {
                LOG.warn("Codex thread ID changed unexpectedly: " + currentSessionId + " -> " + threadId
                        + ". Keeping original thread ID to prevent session split.");
                return;
            }
            state.setSessionId(threadId);
            callbackHandler.notifySessionIdReceived(threadId);
            LOG.info("Captured Codex thread ID: " + threadId);
        }
    }

    /**
     * Handle the result message containing usage statistics.
     *
     * @param jsonContent json content
     */
    private void handleResultMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msgJson == null || !msgJson.has("usage") || !msgJson.get("usage").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject usage = msgJson.getAsJsonObject("usage");
            // The result message carries the turn.completed usage, which covers exactly
            // one turn but counts cached tokens inside input_tokens (OpenAI convention).
            // Normalize to the Claude usage schema (input excludes cache) and stamp it
            // as turnUsage for the per-turn token display in the webview.
            com.google.gson.JsonObject turnUsage = buildTurnUsage(usage);
            // turn.completed 的 usage 仅是单轮记账(Codex SDK 某些版本在此暴露会话累积值,且永不含权威
            // context window)。只有 token_count 可更新顶层上下文快照;无 token_count 时保留既有快照。
            boolean updated = attachUsageToLastAssistant(currentTurnContextUsage, turnUsage);
            if (updated) {
                if (currentTurnContextUsage != null) {
                    pushUsageUpdate(currentTurnContextUsage);
                }
                callbackHandler.notifyMessageUpdate(state.getMessages());
                LOG.info("Codex usage applied from result message");
            } else {
                LOG.debug("Codex usage received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Codex result message: " + e.getMessage());
        }
    }

    /**
     * 处理 MSG_USAGE 消息(Claude-schema token 用量)。
     *
     * <p>OpenCode 的 usage 事件经此到达(Codex CLI 也会发 MSG_USAGE 作兜底)。
     * ai-bridge event-mapper 已下发 Claude-schema usage(input_tokens 不含 cache),
     * 故 turnUsage 直接取用本回合最新值,无需 {@link #buildTurnUsage} 归一化
     * (归一化会从已不含 cache 的 input 里再减一次 cache,导致 OpenCode input 错误)。
     * 状态栏通道由 {@link #pushUsageUpdate} 同步(对称 handleResultMessage)。</p>
     *
     * @param jsonContent json content
     */
    private void handleUsageMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msgJson == null || !msgJson.has("usage") || !msgJson.get("usage").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject usage = msgJson.getAsJsonObject("usage");
            boolean updated = attachUsageToLastAssistant(usage, usage);
            if (updated) {
                pushUsageUpdate(usage);
                callbackHandler.notifyMessageUpdate(state.getMessages());
                LOG.debug("Codex usage applied from usage message");
            } else {
                LOG.debug("Codex usage received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse usage message: " + e.getMessage());
        }
    }

    /**
     * Build a whole-turn usage object in the Claude usage schema from a Codex usage
     * object whose input_tokens include cached tokens.
     *
     * @param usage usage in ai-bridge Claude-compatible format (input includes cache)
     * @return turn usage with input_tokens excluding cache, cache fields separate
     * @since 1.0.0
     */
    private static com.google.gson.JsonObject buildTurnUsage(com.google.gson.JsonObject usage) {
        // 项2:has() 不够——值为 JsonNull 时 getAsInt() 抛异常被外层 catch 静默吞,整笔 usage 丢失。
        // 统一用 jsonIntOrZero 守卫(仅 primitive 才取值);cacheRead 保留"缺失 vs 0"语义(fallback 到 cached_input_tokens)。
        int input = jsonIntOrZero(usage, "input_tokens");
        int output = jsonIntOrZero(usage, "output_tokens");
        int cacheRead;
        if (usage.has("cache_read_input_tokens") && usage.get("cache_read_input_tokens").isJsonPrimitive()) {
            cacheRead = usage.get("cache_read_input_tokens").getAsInt();
        } else if (usage.has("cached_input_tokens") && usage.get("cached_input_tokens").isJsonPrimitive()) {
            cacheRead = usage.get("cached_input_tokens").getAsInt();
        } else {
            cacheRead = 0;
        }
        com.google.gson.JsonObject turnUsage = new com.google.gson.JsonObject();
        turnUsage.addProperty("input_tokens", Math.max(0, input - cacheRead));
        turnUsage.addProperty("cache_creation_input_tokens", 0);
        turnUsage.addProperty("cache_read_input_tokens", cacheRead);
        turnUsage.addProperty("output_tokens", output);
        return turnUsage;
    }

    /** 项2:安全取 JsonObject 整数字段——has() 不够,JsonNull/非 primitive 的 getAsInt() 会抛异常被吞。 */
    private static int jsonIntOrZero(com.google.gson.JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive()
                ? obj.get(key).getAsInt()
                : 0;
    }

    /**
     * Handle event_msg containing token_count and other events.
     *
     * @param jsonContent json content
     */
    private void handleEventMessage(String jsonContent) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject msgJson = gson.fromJson(jsonContent, com.google.gson.JsonObject.class);
            if (msgJson == null || !msgJson.has("payload") || !msgJson.get("payload").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject payload = msgJson.getAsJsonObject("payload");
            if (!payload.has("type") || !CliConstants.CODEX_MSG_TOKEN_COUNT.equals(payload.get("type").getAsString())) {
                return;
            }

            if (!payload.has("info") || payload.get("info").isJsonNull() || !payload.get("info").isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject info = payload.getAsJsonObject("info");
            if (!info.has("last_token_usage") || !info.get("last_token_usage").isJsonObject()) {
                // total_token_usage is cumulative across the whole session and can
                // exceed the active model window. It is valid only for Node-side
                // per-turn delta calculation, never as the current-context numerator.
                LOG.debug("Ignoring Codex token_count without last_token_usage");
                return;
            }
            com.google.gson.JsonObject contextUsage = info.getAsJsonObject("last_token_usage");

            // last_token_usage 是当前活跃模型上下文快照(权威 numerator);total_token_usage 是
            // 会话累积计数,可能超过模型窗口,仅可用于 Node 侧 per-turn delta 计算,绝不可作当前上下文。
            int inputTokens = jsonIntOrZero(contextUsage, "input_tokens");
            int outputTokens = jsonIntOrZero(contextUsage, "output_tokens");
            int cachedInputTokens = jsonIntOrZero(contextUsage, "cached_input_tokens");

            com.google.gson.JsonObject usage = new com.google.gson.JsonObject();
            usage.addProperty("input_tokens", inputTokens);
            usage.addProperty("output_tokens", outputTokens);
            usage.addProperty("cache_read_input_tokens", cachedInputTokens);
            usage.addProperty("cache_creation_input_tokens", 0);
            int modelContextWindow = jsonIntOrZero(info, "model_context_window");
            if (modelContextWindow > 0) {
                usage.addProperty("model_context_window", modelContextWindow);
            }
            currentTurnContextUsage = usage.deepCopy();

            // token_count is not turn-scoped, so never stamp it as turnUsage. The latest
            // token usage is used for the context status; cumulative totals are ignored.
            boolean updated = attachUsageToLastAssistant(usage, null);
            if (updated) {
                pushUsageUpdate(usage);
                callbackHandler.notifyMessageUpdate(state.getMessages());
                LOG.debug("Codex token_count applied: input=" + inputTokens + ", output=" + outputTokens + ", cached=" + cachedInputTokens);
            } else {
                LOG.debug("Codex token_count received but no assistant message to attach");
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Codex event_msg: " + e.getMessage());
        }
    }

    /**
     * Attach usage data to the last assistant message's raw field.
     * The top-level usage field feeds the context-usage status bar; the optional
     * turnUsage field feeds the per-turn token display in the webview.
     *
     * @param usage usage for the status bar (top-level usage field)
     * @param turnUsage whole-turn usage in Claude schema, or null to skip
     * @return boolean
     */
    private boolean attachUsageToLastAssistant(com.google.gson.JsonObject usage, com.google.gson.JsonObject turnUsage) {
        java.util.List<Message> messages = state.getMessagesReference();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.type == Message.Type.ASSISTANT && msg.raw != null) {
                if (usage != null) {
                    msg.raw.add("usage", usage);
                }
                if (turnUsage != null) {
                    msg.raw.add("turnUsage", turnUsage);
                    Double turnCostUsd = UsageCostCalculator.calculateTurnCostUsd(
                            ProviderType.CODEX.value(),
                            turnUsage,
                            state.getModel()
                    );
                    if (turnCostUsd != null) {
                        msg.raw.addProperty(CommonConstants.JSON_KEY_TURN_COST_USD, turnCostUsd);
                    } else {
                        msg.raw.remove(CommonConstants.JSON_KEY_TURN_COST_USD);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void pushUsageUpdate(com.google.gson.JsonObject usage) {
        int maxTokens = state.getEffectiveMaxTokens();
        callbackHandler.notifyUsageUpdate(
                TokenUsageUtils.buildUsageUpdatePayload(usage, state.getProvider(), maxTokens).toString()
        );
    }

    /**
     * Parse a server message with full filtering and parsing logic (ported from v0.1.3-codex).
     *
     * @param msg msg
     * @param messageType message type
     * @return message
     */
    private Message parseServerMessage(com.google.gson.JsonObject msg, Message.Type messageType) {
        if (isMetaMessage(msg)) {
            return null;
        }
        if (isFilteredCommandMessage(msg, messageType)) {
            return null;
        }

        String content = extractMessageContent(msg);
        if (messageType == Message.Type.USER) {
            return buildUserMessage(msg, content);
        }

        Message result = new Message(messageType, content != null ? content : "");
        result.raw = msg;
        return result;
    }

    private boolean isMetaMessage(com.google.gson.JsonObject msg) {
        return msg.has("isMeta") && msg.get("isMeta").getAsBoolean();
    }

    /**
     * Detect Codex internal command messages that must not be shown to the user.
     * Codex prepends instruction blocks to user input; strip them before checking command tags
     * so those hidden blocks do not mask the user's real message.
     * Only filter user messages - assistant messages may contain these tags in code examples.
     */
    private boolean isFilteredCommandMessage(com.google.gson.JsonObject msg, Message.Type messageType) {
        // Only filter user messages - assistant messages may contain command tags in code examples
        if (messageType != Message.Type.USER) {
            return false;
        }

        if (!msg.has("message") || !msg.get("message").isJsonObject()) {
            return false;
        }
        com.google.gson.JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content")) {
            return false;
        }

        String contentStr = extractFirstTextContent(message.get("content"));
        if (contentStr == null) {
            return false;
        }

        String filterContent = CodexMessageConverter.stripSystemTags(contentStr);
        boolean hasCommandMessage = contentStr.contains(CommonConstants.TAG_COMMAND_MESSAGE_OPEN)
            && contentStr.contains(CommonConstants.TAG_COMMAND_MESSAGE_CLOSE);
        if (hasCommandMessage) {
            return false;
        }
        return filterContent.contains(CommonConstants.TAG_COMMAND_NAME)
            || filterContent.contains(CommonConstants.TAG_LOCAL_COMMAND_STDOUT)
            || filterContent.contains(CommonConstants.TAG_LOCAL_COMMAND_STDERR)
            || filterContent.contains(CommonConstants.TAG_COMMAND_ARGS);
    }

    private String extractFirstTextContent(com.google.gson.JsonElement contentElement) {
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }
        if (!contentElement.isJsonArray()) {
            return null;
        }
        com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
        for (int i = 0; i < contentArray.size(); i++) {
            com.google.gson.JsonElement element = contentArray.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject block = element.getAsJsonObject();
            if (block.has("type") && CommonConstants.BLOCK_TYPE_TEXT.equals(block.get("type").getAsString())
                && block.has("text")) {
                return block.get("text").getAsString();
            }
        }
        return null;
    }

    private Message buildUserMessage(com.google.gson.JsonObject msg, String content) {
        boolean hasToolResult = containsToolResult(msg);
        com.google.gson.JsonArray restoredImageBlocks = new com.google.gson.JsonArray();
        if (!hasToolResult) {
            restoredImageBlocks = CodexMessageConverter.restoreCodexImagePlaceholderBlocks(content);
            content = CodexMessageConverter.stripSystemTags(content);
            if ((content != null && !content.trim().isEmpty()) || !restoredImageBlocks.isEmpty()) {
                rewriteUserRawContent(msg, restoredImageBlocks, content);
            }
        }
        if (content == null || content.trim().isEmpty()) {
            if (hasToolResult) {
                markSyntheticToolResultRaw(msg);
                Message result = new Message(Message.Type.USER, CommonConstants.TOOL_RESULT_PLACEHOLDER);
                result.raw = msg;
                return result;
            }
            if (restoredImageBlocks.isEmpty()) {
                return null;
            }
            content = "";
        }

        Message result = new Message(Message.Type.USER, content);
        result.raw = msg;
        return result;
    }

    private void markSyntheticToolResultRaw(com.google.gson.JsonObject msg) {
        com.google.gson.JsonObject origin = new com.google.gson.JsonObject();
        origin.addProperty("kind", "tool_result");
        msg.add("origin", origin);
    }

    /**
     * Extract message content (ported from v0.1.3-codex).
     *
     * @param msg msg
     * @return string
     */
    private String extractMessageContent(com.google.gson.JsonObject msg) {
        if (!msg.has("message")) {
            // Try to get content directly from the top level (some message formats may differ)
            if (msg.has("content")) {
                return extractContentFromElement(msg.get("content"));
            }
            return "";
        }

        com.google.gson.JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }

        // Get the content element
        com.google.gson.JsonElement contentElement = message.get("content");
        return extractContentFromElement(contentElement);
    }

    /**
     * Extract content from a JsonElement (ported from v0.1.3-codex).
     *
     * @param contentElement content element
     * @return string
     */
    private String extractContentFromElement(com.google.gson.JsonElement contentElement) {
        // String format
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        // Array format
        if (contentElement.isJsonArray()) {
            com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            boolean hasContent = false;

            for (int i = 0; i < contentArray.size(); i++) {
                com.google.gson.JsonElement element = contentArray.get(i);
                if (element.isJsonObject()) {
                    com.google.gson.JsonObject block = element.getAsJsonObject();
                    String blockType = (block.has("type") && !block.get("type").isJsonNull())
                        ? block.get("type").getAsString()
                        : null;

                    // Handle different content block types
                    if ((CommonConstants.BLOCK_TYPE_TEXT.equals(blockType) || CommonConstants.BLOCK_TYPE_INPUT_TEXT.equals(blockType) || CommonConstants.BLOCK_TYPE_OUTPUT_TEXT.equals(blockType))
                            && block.has("text") && !block.get("text").isJsonNull()) {
                        String text = block.get("text").getAsString();
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                        hasContent = true;
                    } else if (CommonConstants.BLOCK_TYPE_TOOL_USE.equals(blockType)) {
                        // Skip tool_use, don't display tool usage text
                    } else if (CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(blockType)) {
                        // Tool result - skip display as it provides no direct value to the user
                        // and is typically long and already reflected in the assistant's response
                    } else if (CommonConstants.BLOCK_TYPE_THINKING.equals(blockType)) {
                        // Skip thinking block, don't display fixed text
                    } else if (CommonConstants.BLOCK_TYPE_IMAGE.equals(blockType)) {
                        // Skip image block, don't display fixed text
                    }
                } else if (element.isJsonPrimitive()) {
                    // In some cases, array elements may be plain strings
                    String text = element.getAsString();
                    if (text != null && !text.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                        hasContent = true;
                    }
                }
            }

            return sb.toString();
        }

        // Object format (special cases)
        if (contentElement.isJsonObject()) {
            com.google.gson.JsonObject contentObj = contentElement.getAsJsonObject();
            // Try to extract the text field
            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                return contentObj.get("text").getAsString();
            }
            LOG.warn("Content is an object but has no 'text' field: " + contentObj.toString());
        }

        return "";
    }

    /**
     * Check whether a server message contains a tool_result block.
     *
     * @param msg msg
     * @return boolean
     */
    private boolean containsToolResult(com.google.gson.JsonObject msg) {
        com.google.gson.JsonElement contentElement = getMessageContentElement(msg);
        if (contentElement == null || !contentElement.isJsonArray()) {
            return false;
        }

        com.google.gson.JsonArray contentArray = contentElement.getAsJsonArray();
        for (int i = 0; i < contentArray.size(); i++) {
            com.google.gson.JsonElement element = contentArray.get(i);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject block = element.getAsJsonObject();
                if (block.has("type") && CommonConstants.BLOCK_TYPE_TOOL_RESULT.equals(block.get("type").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Replace raw user content with the visible text only.
     *
     * @param msg msg
     * @param content visible content
     */
    private void rewriteUserRawContent(
            com.google.gson.JsonObject msg,
            com.google.gson.JsonArray imageBlocks,
            String content
    ) {
        com.google.gson.JsonArray existingContent = null;
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            com.google.gson.JsonObject message = msg.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                existingContent = message.getAsJsonArray("content");
            }
        } else if (msg.has("content") && msg.get("content").isJsonArray()) {
            existingContent = msg.getAsJsonArray("content");
        }

        com.google.gson.JsonArray contentBlocks = new com.google.gson.JsonArray();
        boolean textUpdated = false;
        if (existingContent != null) {
            for (com.google.gson.JsonElement element : existingContent) {
                if (!element.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject block = element.getAsJsonObject().deepCopy();
                String blockType = block.has("type") && !block.get("type").isJsonNull()
                        ? block.get("type").getAsString()
                        : null;
                if (CommonConstants.BLOCK_TYPE_TEXT.equals(blockType)
                        || CommonConstants.BLOCK_TYPE_INPUT_TEXT.equals(blockType)
                        || CommonConstants.BLOCK_TYPE_OUTPUT_TEXT.equals(blockType)) {
                    if (!textUpdated) {
                        appendUserContentBlocks(contentBlocks, imageBlocks, content);
                        textUpdated = true;
                    }
                } else {
                    contentBlocks.add(block);
                }
            }
        }

        if (!textUpdated) {
            appendUserContentBlocks(contentBlocks, imageBlocks, content);
        }

        if (msg.has("message") && msg.get("message").isJsonObject()) {
            msg.getAsJsonObject("message").add("content", contentBlocks);
        } else {
            msg.add("content", contentBlocks);
        }
    }

    private void appendUserContentBlocks(
            com.google.gson.JsonArray target,
            com.google.gson.JsonArray imageBlocks,
            String content
    ) {
        com.google.gson.JsonArray visibleBlocks = CodexMessageConverter.userContentBlocks(imageBlocks, content);
        for (com.google.gson.JsonElement block : visibleBlocks) {
            target.add(block.deepCopy());
        }
    }

    /**
     * Get the content element from either nested or top-level Codex message shapes.
     *
     * @param msg msg
     * @return element
     */
    private com.google.gson.JsonElement getMessageContentElement(com.google.gson.JsonObject msg) {
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            com.google.gson.JsonObject message = msg.getAsJsonObject("message");
            if (message.has("content") && !message.get("content").isJsonNull()) {
                return message.get("content");
            }
        }
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            return msg.get("content");
        }
        return null;
    }

    /**
     * Handle content delta in streaming mode.
     *
     * @param content content
     */
    private void handleContentDelta(String content) {
        // Empty content check (compatible with v0.1.3-codex)
        if (content == null || content.isEmpty()) {
            return;
        }

        // Text content arrives after thinking ends
        resetThinkingStatus();

        // Deduplicate deltas already covered by conservative full-message sync
        String novelContent = replayDedup.consumeContentDelta(content);
        if (novelContent.isEmpty()) {
            LOG.debug("Skipping replayed Codex content delta (len=" + content.length() + ")");
            return;
        }

        String acceptedContent = CliOutputLimits.appendBounded(
                assistantContent, novelContent, CliOutputLimits.MAX_ASSISTANT_CHARS);
        if (acceptedContent.isEmpty()) {
            LOG.debug("Codex assistant content limit reached, dropping further content delta");
            return;
        }
        ensureCurrentAssistantMessageExists();
        currentAssistantMessage.content = assistantContent.toString();
        applyTextDeltaToRaw(acceptedContent);

        callbackHandler.notifyContentDelta(acceptedContent);
        // During streaming, skip full message update to avoid JCEF IPC overload.
        // Content deltas (via onContentDelta) provide real-time character display;
        // pushing full JSON on every delta would block the renderer and stall deltas.
        if (!isStreaming) {
            callbackHandler.notifyMessageUpdate(state.getMessages());
        }
    }

    /**
     * Handle thinking delta in streaming mode.
     *
     * @param content content
     */
    private void handleThinkingDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        // Deduplicate thinking deltas
        String novelContent = replayDedup.consumeThinkingDelta(content);
        if (novelContent.isEmpty()) {
            LOG.debug("Skipping replayed Codex thinking delta (len=" + content.length() + ")");
            return;
        }

        ensureCurrentAssistantMessageExists();
        String acceptedThinking = limitThinkingDelta(novelContent);
        if (acceptedThinking.isEmpty()) {
            LOG.debug("Codex reasoning limit reached, dropping further thinking delta");
            return;
        }
        applyThinkingDeltaToRaw(acceptedThinking);
        callbackHandler.notifyThinkingDelta(acceptedThinking);
        // Only push full message update when not streaming (same pattern as content delta)
        if (!isStreaming) {
            callbackHandler.notifyMessageUpdate(state.getMessages());
        }
    }

    /**
     * Handle thinking start signal.
     */
    private void handleThinkingMessage() {
        if (!isThinking) {
            isThinking = true;
            callbackHandler.notifyThinkingStatusChanged(true);
            LOG.debug("Codex thinking started");
        }
    }

    private void resetThinkingStatus() {
        if (isThinking) {
            isThinking = false;
            callbackHandler.notifyThinkingStatusChanged(false);
        }
    }

    /**
     * 确保当前存在一个有效的 assistant 消息用于流式 raw 更新。
     * 如果不存在则创建空的 assistant 消息并添加到消息列表；
     * 如果 raw 为 null 则通过 {@link RawMessageHelper#ensureAssistantRaw} 初始化结构。
     */
    private void ensureCurrentAssistantMessageExists() {
        if (currentAssistantMessage == null) {
            com.google.gson.JsonObject raw = RawMessageHelper.ensureAssistantRaw(null);
            currentAssistantMessage = new Message(Message.Type.ASSISTANT, "", raw);
            state.addMessage(currentAssistantMessage);
        }
        if (currentAssistantMessage.raw == null) {
            currentAssistantMessage.raw = RawMessageHelper.ensureAssistantRaw(null);
        }
    }

    /**
     * 将思考增量追加到当前 assistant raw 中的 thinking 块。
     * 委托给 {@link RawMessageHelper#applyThinkingDelta} 统一实现。
     *
     * @param delta 思考增量文本
     */
    private void applyThinkingDeltaToRaw(String delta) {
        RawMessageHelper.applyThinkingDelta(currentAssistantMessage.raw, delta);
    }

    private String limitThinkingDelta(String delta) {
        String existing = currentAssistantMessage != null && currentAssistantMessage.raw != null
                ? ReplayDeduplicator.extractThinkingContent(currentAssistantMessage.raw) : "";
        int remaining = CliOutputLimits.MAX_REASONING_CHARS - existing.length();
        if (remaining <= 0) {
            return "";
        }
        return delta.length() <= remaining ? delta : delta.substring(0, remaining);
    }

    /**
     * 将文本增量追加到当前 assistant raw 中的 text 块。
     * 委托给 {@link RawMessageHelper#applyTextDelta} 统一实现。
     *
     * @param delta 文本增量
     */
    private void applyTextDeltaToRaw(String delta) {
        RawMessageHelper.applyTextDelta(currentAssistantMessage.raw, delta);
    }

    /**
     * Handle Stream Start
     *
     */
    private void handleStreamStart() {
        isStreaming = true;
        streamEndedThisTurn = false;
        replayDedup.reset();
        currentTurnContextUsage = null;
        resetStreamingAccumulator();
        callbackHandler.notifyStreamStart();
        LOG.debug("Codex stream started");
    }

    /**
     * Handle Stream End
     *
     */
    private void handleStreamEnd() {
        if (!isStreaming && streamEndedThisTurn) {
            return;
        }

        isStreaming = false;
        streamEndedThisTurn = true;
        resetThinkingStatus();
        replayDedup.reset();
        callbackHandler.notifyStreamCompleted();
        callbackHandler.notifyMessageUpdate(state.getMessages());
        callbackHandler.notifyStreamEnd();
        state.setBusy(false);
        state.setLoading(false);
        state.updateLastModifiedTime();
        resetStreamingAccumulator();
        callbackHandler.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
        LOG.debug("Codex stream ended");
    }

    /**
     * Handle the end of a message.
     *
     */
    private void handleMessageEnd() {
        resetThinkingStatus();
        LOG.debug("Codex message_end received, deferring stream cleanup to stream_end/onComplete");
    }

    private void handleBlockReset() {
        // 项3:block_reset 应在 streaming 期间到达;非 streaming 时收到是乱序残留,清空已显示的
        // assistantContent/currentAssistantMessage 会破坏当前 turn(对比 Claude handleNewTurnStart 有 isStreaming 守卫)。
        if (!isStreaming) {
            LOG.debug("Codex block_reset received while not streaming, ignoring");
            return;
        }
        resetThinkingStatus();
        assistantContent.setLength(0);
        currentAssistantMessage = null;
        replayDedup.reset();
        callbackHandler.notifyBlockReset();
        LOG.debug("Codex block_reset received");
    }

    /**
     * Reset per-turn streaming accumulator state.
     *
     */
    private void resetStreamingAccumulator() {
        assistantContent.setLength(0);
        currentAssistantMessage = null;
        replayDedup.reset();
    }
}
