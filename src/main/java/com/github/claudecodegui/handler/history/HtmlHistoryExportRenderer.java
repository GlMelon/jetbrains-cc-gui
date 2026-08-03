package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/**
 * Renders a standalone, script-free HTML transcript.
 *
 * <p>All provider-controlled values are inserted as escaped text. The renderer never accepts
 * raw HTML, never emits external URLs, and blocks active content with a restrictive CSP.</p>
 */
final class HtmlHistoryExportRenderer implements HistoryExportRenderer {
    private static final String HTML_PREFIX = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta http-equiv="Content-Security-Policy"
                    content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none';
                             font-src 'none'; connect-src 'none'; media-src 'none';
                             object-src 'none'; frame-src 'none'; base-uri 'none';
                             form-action 'none'">
            """;
    private static final String STYLE = """
              <style>
                :root{color-scheme:light dark;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
                body{max-width:960px;margin:0 auto;padding:32px 20px;background:#f7f7f8;color:#202124;line-height:1.55}
                header{margin-bottom:24px;padding:20px;border:1px solid #d9d9df;border-radius:12px;background:#fff}
                h1{margin:0 0 8px;font-size:24px;overflow-wrap:anywhere}.meta{color:#666;font-size:13px}.notice{margin-top:12px;padding:10px 12px;border-radius:8px;background:#fff3cd;color:#664d03}
                main{display:flex;flex-direction:column;gap:14px}
                .message{border:1px solid #d9d9df;border-radius:12px;background:#fff;overflow:hidden}
                .message-head{display:flex;justify-content:space-between;gap:12px;padding:10px 14px;background:#f0f1f3;font-size:13px}
                .role{font-weight:700}
                .timestamp{color:#666;overflow-wrap:anywhere}
                .message-body{padding:14px}
                .block+.block{margin-top:12px}
                pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.55 ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace}
                .thinking,.tool{padding:10px 12px;border-left:3px solid #8b5cf6;background:#f6f3ff;border-radius:6px}
                .tool{border-left-color:#0a84ff;background:#eef6ff}
                .tool-title{margin-bottom:6px;font-weight:700}
                .image-omitted{color:#777;font-style:italic}
                @media(prefers-color-scheme:dark){
                  body{background:#171719;color:#ececf1}
                  header,.message{background:#232326;border-color:#3a3a40}
                  .message-head{background:#2d2d31}
                  .meta,.timestamp{color:#aaa}
                  .notice{background:#4a3c11;color:#ffe69c}
                  .thinking{background:#302a43}
                  .tool{background:#1d3044}
                }
              </style>
            """;

    @Override
    public HistoryExportFormat format() {
        return HistoryExportFormat.HTML;
    }

    @Override
    public String render(HistoryExportDocument document) {
        StringBuilder html = new StringBuilder();
        html.append(HTML_PREFIX)
                .append("  <title>").append(escape(document.title())).append("</title>\n")
                .append(STYLE)
                .append("</head>\n<body>\n<header>\n  <h1>").append(escape(document.title())).append("</h1>\n")
                .append("  <div class=\"meta\">Session: ").append(escape(document.sessionId())).append("</div>\n")
                .append("  <div class=\"meta\">Exported messages: ").append(document.exportedMessageCount())
                .append(" / ").append(document.totalMessageCount()).append("</div>\n");
        if (document.truncated()) {
            html.append("  <div class=\"notice\">This export was truncated. Omitted messages: ")
                    .append(document.omittedMessageCount()).append(".</div>\n");
        }
        html.append("</header>\n<main>\n");
        for (JsonObject message : document.messages()) {
            appendMessage(html, message == null ? new JsonObject() : message);
        }
        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void appendMessage(StringBuilder html, JsonObject message) {
        String role = resolveRole(message);
        String timestamp = resolveString(message, CommonConstants.JSON_KEY_TIMESTAMP, CommonConstants.JSON_KEY_CREATED_AT, CommonConstants.JSON_KEY_TIME);
        html.append("  <article class=\"message\">\n    <div class=\"message-head\"><span class=\"role\">")
                .append(escape(role)).append("</span><span class=\"timestamp\">")
                .append(escape(timestamp)).append("</span></div>\n    <div class=\"message-body\">\n");

        JsonElement content = resolveContent(message);
        if (content == null || content.isJsonNull()) {
            appendPreBlock(html, "block", GsonHolder.GSON.toJson(message));
        } else if (content.isJsonArray()) {
            JsonArray blocks = content.getAsJsonArray();
            if (blocks.isEmpty()) {
                appendPreBlock(html, "block", "");
            } else {
                for (JsonElement block : blocks) {
                    appendContentBlock(html, block);
                }
            }
        } else {
            appendContentBlock(html, content);
        }
        html.append("    </div>\n  </article>\n");
    }

    private static void appendContentBlock(StringBuilder html, JsonElement block) {
        if (block == null || block.isJsonNull()) {
            appendPreBlock(html, "block", "");
            return;
        }
        if (block.isJsonPrimitive()) {
            appendPreBlock(html, "block", primitiveText(block.getAsJsonPrimitive()));
            return;
        }
        if (!block.isJsonObject()) {
            appendPreBlock(html, "block", GsonHolder.GSON.toJson(block));
            return;
        }

        JsonObject object = block.getAsJsonObject();
        String type = resolveString(object, CommonConstants.JSON_KEY_TYPE);
        switch (type) {
            case CommonConstants.BLOCK_TYPE_TEXT, CommonConstants.BLOCK_TYPE_INPUT_TEXT,
                 CommonConstants.BLOCK_TYPE_OUTPUT_TEXT ->
                    appendPreBlock(html, "block",
                            resolveString(object, CommonConstants.JSON_KEY_TEXT,
                                    CommonConstants.JSON_KEY_CONTENT));
            case CommonConstants.BLOCK_TYPE_THINKING, CommonConstants.BLOCK_TYPE_REASONING ->
                    appendPreBlock(html, "block thinking",
                            resolveString(object, CommonConstants.JSON_KEY_THINKING,
                                    CommonConstants.JSON_KEY_TEXT, CommonConstants.JSON_KEY_CONTENT));
            case CommonConstants.BLOCK_TYPE_TOOL_USE -> appendToolUse(html, object);
            case CommonConstants.BLOCK_TYPE_TOOL_RESULT -> appendToolResult(html, object);
            case CommonConstants.BLOCK_TYPE_IMAGE -> html.append("      <div class=\"block image-omitted\">[Image omitted from safe HTML export]</div>\n");
            case CommonConstants.BLOCK_TYPE_TASK_NOTIFICATION -> appendPreBlock(html, "block", resolveString(object, CommonConstants.JSON_KEY_SUMMARY, CommonConstants.JSON_KEY_TEXT));
            default -> appendPreBlock(html, "block", GsonHolder.GSON.toJson(object));
        }
    }

    private static void appendToolUse(StringBuilder html, JsonObject block) {
        html.append("      <section class=\"block tool\"><div class=\"tool-title\">Tool: ")
                .append(escape(resolveString(block, CommonConstants.JSON_KEY_NAME))).append("</div><pre>")
                .append(escape(jsonOrString(block.get(CommonConstants.JSON_KEY_INPUT)))).append("</pre></section>\n");
    }

    private static void appendToolResult(StringBuilder html, JsonObject block) {
        String label = booleanValue(block.get(CommonConstants.JSON_KEY_IS_ERROR)) ? "Tool result (error)" : "Tool result";
        html.append("      <section class=\"block tool\"><div class=\"tool-title\">")
                .append(label).append("</div><pre>")
                .append(escape(jsonOrString(block.get(CommonConstants.JSON_KEY_CONTENT)))).append("</pre></section>\n");
    }

    private static void appendPreBlock(StringBuilder html, String cssClass, String text) {
        html.append("      <pre class=\"").append(cssClass).append("\">")
                .append(escape(text)).append("</pre>\n");
    }

    private static JsonElement resolveContent(JsonObject message) {
        JsonElement direct = meaningful(message.get(CommonConstants.JSON_KEY_CONTENT));
        if (direct != null) {
            return direct;
        }
        JsonObject nestedMessage = object(message.get(CommonConstants.JSON_KEY_MESSAGE));
        JsonElement nested = nestedMessage == null ? null : meaningful(nestedMessage.get(CommonConstants.JSON_KEY_CONTENT));
        if (nested != null) {
            return nested;
        }
        JsonObject raw = object(message.get(CommonConstants.JSON_KEY_RAW));
        if (raw == null) {
            return null;
        }
        JsonObject rawMessage = object(raw.get(CommonConstants.JSON_KEY_MESSAGE));
        JsonElement rawNested = rawMessage == null ? null : meaningful(rawMessage.get(CommonConstants.JSON_KEY_CONTENT));
        return rawNested != null ? rawNested : meaningful(raw.get(CommonConstants.JSON_KEY_CONTENT));
    }

    private static String resolveRole(JsonObject message) {
        String role = resolveString(message, CommonConstants.JSON_KEY_ROLE, CommonConstants.JSON_KEY_TYPE);
        if (!role.isBlank()) {
            return displayRole(role);
        }
        JsonObject nestedMessage = object(message.get(CommonConstants.JSON_KEY_MESSAGE));
        role = nestedMessage == null ? "" : resolveString(nestedMessage, CommonConstants.JSON_KEY_ROLE, CommonConstants.JSON_KEY_TYPE);
        if (!role.isBlank()) {
            return displayRole(role);
        }
        JsonObject raw = object(message.get(CommonConstants.JSON_KEY_RAW));
        JsonObject rawMessage = raw == null ? null : object(raw.get(CommonConstants.JSON_KEY_MESSAGE));
        role = rawMessage == null ? "" : resolveString(rawMessage, CommonConstants.JSON_KEY_ROLE, CommonConstants.JSON_KEY_TYPE);
        return role.isBlank() ? "Message" : displayRole(role);
    }

    private static String displayRole(String role) {
        return switch (role.toLowerCase(Locale.ROOT)) {
            case CommonConstants.MSG_TYPE_USER, CommonConstants.MSG_TYPE_HUMAN -> "User";
            case CommonConstants.MSG_TYPE_ASSISTANT, CommonConstants.MSG_TYPE_AI -> "Assistant";
            case CommonConstants.MSG_TYPE_SYSTEM -> "System";
            case CommonConstants.MSG_TYPE_TOOL, CommonConstants.MSG_TYPE_TOOL_RESULT, CommonConstants.MSG_TYPE_TOOL_USE -> "Tool";
            default -> role;
        };
    }

    private static String resolveString(JsonObject object, String... keys) {
        if (object == null) {
            return "";
        }
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonElement meaningful(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().isBlank()) {
            return null;
        }
        return value;
    }

    private static String jsonOrString(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        return GsonHolder.GSON.toJson(value);
    }

    private static String primitiveText(JsonPrimitive primitive) {
        return primitive.isString() ? primitive.getAsString() : primitive.toString();
    }

    private static boolean booleanValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static String escape(String value) {
        return HistoryHtmlSanitizer.escape(value);
    }
}

