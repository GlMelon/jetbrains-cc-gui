package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Message parser.
 * Parses server-returned messages and converts them to Message objects.
 */
public class MessageParser {
    private static final Logger LOG = Logger.getInstance(MessageParser.class);
    private static final String NO_RESPONSE_REQUESTED = "No response requested.";

    /**
     * Parse a server-returned message.
     */
    public ClaudeSession.Message parseServerMessage(JsonObject msg) {
        String type = msg.has("type") ? msg.get("type").getAsString() : null;

        // Filter out isMeta messages
        if (msg.has("isMeta") && msg.get("isMeta").getAsBoolean()) {
            return null;
        }

        // Filter out sidechain messages (subagent transcripts) so they never
        // enter the main session list. This mirrors the isSidechain filter
        // ClaudeSessionLiteReader applies on history reload, keeping reloaded
        // history consistent with the live stream (whose subagent messages are
        // already filtered upstream by ai-bridge's parent_tool_use_id check).
        // parseServerMessage runs on the history-reload path, so this is a
        // defense-in-depth guard against any isSidechain-tagged entry slipping
        // through into the rendered chat.
        if (msg.has("isSidechain") && !msg.get("isSidechain").isJsonNull()
                && msg.get("isSidechain").getAsBoolean()) {
            return null;
        }

        // Filter out command messages - only for user messages
        // Assistant messages may contain these tags in code examples
        if (shouldFilterCommandMessage(msg, type)) {
            return null;
        }

        // Claude Code uses this assistant placeholder for commands that do not need a response.
        if (CommonConstants.MSG_TYPE_ASSISTANT.equals(type)
                && NO_RESPONSE_REQUESTED.equals(extractMessageContent(msg).trim())) {
            return null;
        }

        if (CommonConstants.MSG_TYPE_USER.equals(type)) {
            // Unwrap normalized envelope: if envelope contains "raw", use it as the actual raw payload
            JsonObject actualRaw = msg.has("raw") && msg.get("raw").isJsonObject()
                    ? msg.getAsJsonObject("raw") : msg;
            JsonObject boundedRaw = CliOutputLimits.boundedJsonObjectCopy(actualRaw);
            String content = extractMessageContent(msg);
            // Check if it contains a tool_result
            if (content == null || content.trim().isEmpty()) {
                if (hasToolResult(actualRaw)) {
                    return new ClaudeSession.Message(ClaudeSession.Message.Type.USER, CommonConstants.TOOL_RESULT_PLACEHOLDER, boundedRaw);
                }
                if (hasImageContent(actualRaw)) {
                    return new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "", boundedRaw);
                }
                return null;
            }
            return new ClaudeSession.Message(ClaudeSession.Message.Type.USER, content, boundedRaw);
        } else if (CommonConstants.MSG_TYPE_ASSISTANT.equals(type)) {
            String content = extractMessageContent(msg);
            // Unwrap normalized envelope: if envelope contains "raw", use it as the actual raw payload
            JsonObject actualRaw = msg.has("raw") && msg.get("raw").isJsonObject()
                    ? msg.getAsJsonObject("raw") : msg;
            JsonObject boundedRaw = CliOutputLimits.boundedJsonObjectCopy(actualRaw);
            return new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, content, boundedRaw);
        }

        return null;
    }

    /**
     * Check whether a command message should be filtered out.
     * Only applies to user messages - assistant messages may contain
     * command tags in code examples and should not be filtered.
     */
    private boolean shouldFilterCommandMessage(JsonObject msg, String type) {
        // Only filter user messages - assistant messages may contain command tags in code examples
        if (!CommonConstants.MSG_TYPE_USER.equals(type)) {
            return false;
        }

        if (!msg.has("message") || !msg.get("message").isJsonObject()) {
            return false;
        }

        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content")) {
            return false;
        }

        JsonElement contentElement = message.get("content");
        String contentStr = null;

        if (contentElement.isJsonPrimitive()) {
            contentStr = contentElement.getAsString();
        } else if (contentElement.isJsonArray()) {
            JsonArray contentArray = contentElement.getAsJsonArray();
            for (int i = 0; i < contentArray.size(); i++) {
                JsonElement element = contentArray.get(i);
                if (element.isJsonObject()) {
                    JsonObject block = element.getAsJsonObject();
                    if (block.has("type") && CommonConstants.BLOCK_TYPE_TEXT.equals(block.get("type").getAsString()) &&
                        block.has("text")) {
                        contentStr = block.get("text").getAsString();
                        break;
                    }
                }
            }
        }

        // Filter content with command tags (allow user input containing <command-message>)
        if (contentStr != null) {
            boolean hasCommandMessage = contentStr.contains(CommonConstants.TAG_COMMAND_MESSAGE_OPEN) &&
                contentStr.contains(CommonConstants.TAG_COMMAND_MESSAGE_CLOSE);
            if (!hasCommandMessage && (
                contentStr.contains(CommonConstants.TAG_COMMAND_NAME) ||
                contentStr.contains(CommonConstants.TAG_LOCAL_COMMAND_STDOUT) ||
                contentStr.contains(CommonConstants.TAG_LOCAL_COMMAND_STDERR) ||
                contentStr.contains(CommonConstants.TAG_COMMAND_ARGS)
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check whether the message contains a tool_result.
     */
    public boolean hasToolResult(JsonObject msg) {
        return hasContentBlockType(msg, "tool_result");
    }

    public boolean hasImageContent(JsonObject msg) {
        return hasContentBlockType(msg, "image");
    }

    private boolean hasContentBlockType(JsonObject msg, String blockType) {
        // Check message.content first (old format)
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            JsonObject message = msg.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                if (contentArrayHasBlockType(message.getAsJsonArray("content"), blockType)) {
                    return true;
                }
            }
        }

        // Check direct content field (normalized format with raw object)
        if (msg.has("content") && msg.get("content").isJsonArray()) {
            if (contentArrayHasBlockType(msg.getAsJsonArray("content"), blockType)) {
                return true;
            }
        }

        return false;
    }

    private boolean contentArrayHasBlockType(JsonArray contentArray, String blockType) {
        for (int i = 0; i < contentArray.size(); i++) {
            JsonElement element = contentArray.get(i);
            if (element.isJsonObject()) {
                JsonObject block = element.getAsJsonObject();
                if (block.has("type") && blockType.equals(block.get("type").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract the message content.
     */
    public String extractMessageContent(JsonObject msg) {
        if (!msg.has("message")) {
            if (msg.has("content")) {
                return extractContentFromElement(msg.get("content"));
            }
            return "";
        }

        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }

        return extractContentFromElement(message.get("content"));
    }

    /**
     * Extract content from a JsonElement.
     */
    private String extractContentFromElement(JsonElement contentElement) {
        if (contentElement.isJsonPrimitive()) {
            return boundedContent(contentElement.getAsString());
        }

        if (contentElement.isJsonArray()) {
            return extractFromArrayContent(contentElement.getAsJsonArray());
        }

        if (contentElement.isJsonObject()) {
            JsonObject contentObj = contentElement.getAsJsonObject();
            if (contentObj.has("text") && !contentObj.get("text").isJsonNull()) {
                return boundedContent(contentObj.get("text").getAsString());
            }
            LOG.warn("Content is an object but has no 'text' field (fields="
                    + contentObj.size() + ")");
        }

        return "";
    }

    /**
     * Extract text from array-format content.
     */
    private String extractFromArrayContent(JsonArray contentArray) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < contentArray.size(); i++) {
            JsonElement element = contentArray.get(i);
            if (element.isJsonObject()) {
                JsonObject block = element.getAsJsonObject();
                String blockType = (block.has("type") && !block.get("type").isJsonNull())
                    ? block.get("type").getAsString()
                    : null;

                if (CommonConstants.BLOCK_TYPE_TEXT.equals(blockType) && block.has("text") && !block.get("text").isJsonNull()) {
                    if (sb.length() > 0) {
                        CliOutputLimits.appendBounded(sb, "\n", CliOutputLimits.MAX_ASSISTANT_CHARS);
                    }
                    CliOutputLimits.appendBounded(
                            sb,
                            block.get("text").getAsString(),
                            CliOutputLimits.MAX_ASSISTANT_CHARS
                    );
                } else if ("tool_use".equals(blockType)) {
                    // Skip tool_use block, don't display tool usage text
                } else if ("thinking".equals(blockType)) {
                    // Skip thinking block, don't display fixed text
                } else if ("image".equals(blockType)) {
                    // Skip image block, don't display fixed text
                }
            } else if (element.isJsonPrimitive()) {
                String text = element.getAsString();
                if (text != null && !text.trim().isEmpty()) {
                    if (sb.length() > 0) {
                        CliOutputLimits.appendBounded(sb, "\n", CliOutputLimits.MAX_ASSISTANT_CHARS);
                    }
                    CliOutputLimits.appendBounded(sb, text, CliOutputLimits.MAX_ASSISTANT_CHARS);
                }
            }
            if (sb.length() >= CliOutputLimits.MAX_ASSISTANT_CHARS) {
                break;
            }
        }

        return sb.toString();
    }

    private String boundedContent(String content) {
        if (content == null || content.length() <= CliOutputLimits.MAX_ASSISTANT_CHARS) {
            return content;
        }
        return content.substring(0, CliOutputLimits.MAX_ASSISTANT_CHARS);
    }
}
