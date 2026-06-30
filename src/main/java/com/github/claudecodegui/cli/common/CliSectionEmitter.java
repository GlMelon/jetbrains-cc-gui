package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;

/**
 * Unified wire-event emitter for CLI runtimes.
 * <p>
 * Provider parsers still own provider-specific semantics: they decide whether a
 * source event is thinking, tool, command, or assistant content. This class only
 * centralizes how those sections are emitted to the existing message handlers.
 */
public final class CliSectionEmitter {
    @FunctionalInterface
    public interface MessageSink {
        void emit(String type, String content);
    }

    private final MessageSink sink;

    public CliSectionEmitter(MessageSink sink) {
        this.sink = sink;
    }

    public void sessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sink.emit(CliConstants.MSG_SESSION_ID, sessionId);
        }
    }

    public void streamStart() {
        sink.emit(CliConstants.MSG_STREAM_START, "");
    }

    public void streamEnd() {
        sink.emit(CliConstants.MSG_STREAM_END, "");
    }

    public void messageStart() {
        sink.emit(CliConstants.MSG_MESSAGE_START, "");
    }

    public void messageEnd() {
        sink.emit(CliConstants.MSG_MESSAGE_END, "");
    }

    public void blockReset() {
        sink.emit(CliConstants.MSG_BLOCK_RESET, "");
    }

    public void status(String text) {
        if (text != null && !text.isBlank()) {
            sink.emit(CliConstants.CODEX_MSG_STATUS, text);
        }
    }

    public void usage(String json) {
        if (json != null && !json.isEmpty()) {
            sink.emit(CliConstants.MSG_USAGE, json);
        }
    }

    public void result(String json) {
        if (json != null && !json.isEmpty()) {
            sink.emit(CliConstants.MSG_RESULT, json);
        }
    }

    public void content(String text) {
        if (text != null && !text.isEmpty()) {
            sink.emit(CliConstants.MSG_CONTENT, text);
        }
    }

    public void contentDelta(StringBuilder assistantContent, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        assistantContent.append(text);
        sink.emit(CliConstants.MSG_CONTENT_DELTA, text);
    }

    public void thinkingStart() {
        sink.emit(CommonConstants.MSG_TYPE_THINKING, "");
    }

    public void thinkingDelta(String text) {
        if (text != null && !text.isEmpty()) {
            sink.emit(CliConstants.MSG_THINKING_DELTA, text);
        }
    }

    public void toolUse(JsonObject block) {
        if (block != null) {
            sink.emit(CommonConstants.MSG_TYPE_TOOL_USE, block.toString());
        }
    }

    public void toolResult(JsonObject block) {
        if (block != null) {
            sink.emit(CommonConstants.MSG_TYPE_TOOL_RESULT, block.toString());
        }
    }

    public void assistantRaw(JsonObject message) {
        if (message != null) {
            sink.emit(CommonConstants.MSG_TYPE_ASSISTANT, message.toString());
        }
    }

    public void userRaw(JsonObject message) {
        if (message != null) {
            sink.emit(CommonConstants.MSG_TYPE_USER, message.toString());
        }
    }
}
