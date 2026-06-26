package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Parses OpenCode SSE event stream into standard callback events.
 * <p>
 * OpenCode uses HTTP SSE (Server-Sent Events) for streaming.
 * This adapter converts OpenCode event format to the standard message callback format.
 */
public class OpenCodeStreamAdapter {

    private static final Logger LOG = Logger.getInstance(OpenCodeStreamAdapter.class);
    private final Gson gson = new Gson();

    /**
     * Process a single SSE event line from OpenCode.
     */
    public void processEvent(String eventData, MessageCallback callback, StringBuilder assistantContent) {
        if (eventData == null || eventData.isBlank()) {
            return;
        }

        try {
            // SSE data lines may be prefixed with "data: "
            String json = eventData.startsWith("data: ") ? eventData.substring(6) : eventData;
            JsonObject event = gson.fromJson(json, JsonObject.class);
            if (event == null) {
                return;
            }

            String type = getString(event, "type");
            if (type == null) {
                return;
            }

            switch (type) {
                case "session.created", "session.started" -> {
                    callback.onMessage("stream_start", "");
                    callback.onMessage("message_start", "");
                }
                case "message.created", "message.updated" -> {
                    if (event.has("message") && event.get("message").isJsonObject()) {
                        JsonObject msg = event.getAsJsonObject("message");
                        String role = getString(msg, "role");
                        if ("assistant".equals(role)) {
                            extractContent(msg, callback, assistantContent);
                        }
                    }
                }
                case "session.completed", "session.done" -> {
                    callback.onMessage("stream_end", "");
                    callback.onMessage("message_end", "");
                }
                case "error" -> {
                    String msg = getString(event, "message");
                    if (msg == null) msg = "Unknown error";
                    callback.onError(msg);
                }
                default -> {
                    // Ignore unknown types
                }
            }
        } catch (Exception e) {
            LOG.debug("[OpenCodeStreamAdapter] Failed to parse event: " + e.getMessage());
        }
    }

    private void extractContent(JsonObject msg, MessageCallback callback, StringBuilder assistantContent) {
        if (!msg.has("content") || !msg.get("content").isJsonArray()) {
            return;
        }
        JsonArray content = msg.getAsJsonArray("content");
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String blockType = getString(block, "type");
            if ("text".equals(blockType)) {
                String text = getString(block, "text");
                if (text != null && !text.isEmpty()) {
                    String delta = appendedDelta(assistantContent.toString(), text);
                    if (!delta.isEmpty()) {
                        assistantContent.append(delta);
                        callback.onMessage("content_delta", delta);
                    }
                }
            }
        }
    }

    private static String appendedDelta(String previous, String next) {
        String oldText = previous != null ? previous : "";
        String newText = next != null ? next : "";
        if (newText.isEmpty() || newText.equals(oldText)) {
            return "";
        }
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }
}
