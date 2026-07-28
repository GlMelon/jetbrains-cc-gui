package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.HistoryExportFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HtmlHistoryExportRendererTest {

    @Test
    public void escapesAllProviderControlledTextAndBlocksActiveContent() {
        JsonObject message = new JsonObject();
        message.addProperty(CommonConstants.JSON_KEY_ROLE, "assistant<script>alert(1)</script>");
        message.addProperty(CommonConstants.JSON_KEY_TIMESTAMP, "2026-07-23<img src=x onerror=alert(1)>");

        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TEXT);
        text.addProperty(CommonConstants.JSON_KEY_TEXT, "<script>alert(1)</script><a href=\"javascript:alert(1)\">x</a>");
        content.add(text);

        JsonObject toolUse = new JsonObject();
        toolUse.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_USE);
        toolUse.addProperty(CommonConstants.JSON_KEY_NAME, "tool<img src=x onerror=alert(1)>");
        JsonObject input = new JsonObject();
        input.addProperty(CommonConstants.JSON_KEY_VALUE, "</pre><iframe src=\"https://evil.example\"></iframe>");
        toolUse.add(CommonConstants.JSON_KEY_INPUT, input);
        content.add(toolUse);

        JsonObject toolResult = new JsonObject();
        toolResult.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_RESULT);
        toolResult.addProperty(CommonConstants.JSON_KEY_CONTENT, "<object data=\"evil\"></object>");
        content.add(toolResult);

        JsonObject image = new JsonObject();
        image.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_IMAGE);
        image.addProperty(CommonConstants.JSON_KEY_CONTENT, "data:image/png;base64,secret");
        content.add(image);
        message.add(CommonConstants.JSON_KEY_CONTENT, content);

        String html = new HtmlHistoryExportRenderer().render(document(
                "session<script>alert(2)</script>",
                "Title</title><script>alert(3)</script>",
                List.of(message),
                1,
                1
        ));

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("Content-Security-Policy"));
        assertTrue(html.contains("default-src 'none'"));
        assertTrue(html.contains("object-src 'none'"));
        assertTrue(html.contains("frame-src 'none'"));
        assertTrue(html.contains("base-uri 'none'"));
        assertTrue(html.contains("form-action 'none'"));
        assertTrue(html.contains("Title&lt;/title&gt;&lt;script&gt;alert(3)&lt;/script&gt;"));
        assertTrue(html.contains("session&lt;script&gt;alert(2)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;a href=&quot;javascript:alert(1)&quot;&gt;x&lt;/a&gt;"));
        assertTrue(html.contains("&quot;value&quot;"));
        assertTrue(html.contains("evil.example"));
        assertTrue(html.contains("&lt;object data=&quot;evil&quot;&gt;&lt;/object&gt;"));
        assertTrue(html.contains("[Image omitted from safe HTML export]"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("<object"));
        assertFalse(html.contains("<embed"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("<a href="));
        assertFalse(html.contains("<img src="));
        assertFalse(html.contains("data:image/png;base64,secret"));
    }

    @Test
    public void reportsExactTruncationMetadataWithoutRenderingMissingMessages() {
        JsonObject message = new JsonObject();
        message.addProperty(CommonConstants.JSON_KEY_CONTENT, "visible");

        String html = new HtmlHistoryExportRenderer().render(document(
                "session-1",
                "Demo",
                List.of(message),
                1,
                4
        ));

        assertTrue(html.contains("Exported messages: 1 / 4"));
        assertTrue(html.contains("This export was truncated. Omitted messages: 3."));
        assertTrue(html.contains("visible"));
    }

    private static HistoryExportDocument document(
            String sessionId,
            String title,
            List<JsonObject> messages,
            int exportedMessageCount,
            int totalMessageCount
    ) {
        return new HistoryExportDocument(
                sessionId,
                title,
                messages,
                exportedMessageCount,
                totalMessageCount,
                new HistoryExportPolicy(10, 16_384)
        );
    }
}


