package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClaudeSessionQueryServiceTest {

    @Test
    public void normalizeClaudeHistoryMessagesMarksCheckpointsRegardlessOfRowOrder() {
        String firstMessageId = "11111111-1111-4111-8111-111111111111";
        String secondMessageId = "22222222-2222-4222-8222-222222222222";
        String unavailableMessageId = "33333333-3333-4333-8333-333333333333";

        JsonArray rows = new JsonArray();
        rows.add(createCheckpoint(firstMessageId, false));
        rows.add(createUserMessage("first", firstMessageId));
        rows.add(createUserMessage("second", secondMessageId));
        rows.add(createCheckpoint(secondMessageId, true));
        rows.add(createUserMessage("legacy", unavailableMessageId));

        var normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessages(rows);

        assertEquals(3, normalized.size());
        assertTrue(normalized.get(0).get(CommonConstants.JSON_KEY_REWINDABLE).getAsBoolean());
        assertTrue(normalized.get(1).get(CommonConstants.JSON_KEY_REWINDABLE).getAsBoolean());
        assertFalse(normalized.get(2).get(CommonConstants.JSON_KEY_REWINDABLE).getAsBoolean());
    }

    @Test
    public void normalizeClaudeHistoryMessagesMarksLegacyUsersAsNotRewindable() {
        JsonArray rows = new JsonArray();
        rows.add(createUserMessage("legacy", "44444444-4444-4444-8444-444444444444"));

        var normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessages(rows);

        assertEquals(1, normalized.size());
        assertFalse(normalized.get(0).get(CommonConstants.JSON_KEY_REWINDABLE).getAsBoolean());
    }

    @Test
    public void normalizeClaudeHistoryMessageRestoresImageBlocksAndKeepsUserText() throws IOException {
        Path imagePath = Files.createTempFile("claude-history-image", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(
                    createUserMessage("[Image #1: " + imagePath + "]\n\n"
                            + "The user has attached the image(s) above. Please use the Read tool to view them.\n\n"
                            + "请分析这张图片")
            );

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString()
                    .startsWith("data:image/png;base64,"));
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("previewSrc").getAsString()
                    .startsWith("data:image/png;base64,"));
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("请分析这张图片", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void normalizeClaudeHistoryMessageKeepsImageOnlyPromptAsImageBlock() throws IOException {
        Path imagePath = Files.createTempFile("claude-history-image-only", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(
                    createUserMessage("[Image #1: " + imagePath + "]\n\n"
                            + "The user has attached the image(s) above. Please use the Read tool to view them.")
            );

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(1, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString()
                    .startsWith("data:image/png;base64,"));
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void normalizeClaudeHistoryMessageRestoresBareImagePathLines() throws IOException {
        Path imagePath = Files.createTempFile("claude-history-bare-image", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(
                    createUserMessage("Analyze this image\n\n" + imagePath)
            );

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            JsonObject imageBlock = contentBlocks.get(0).getAsJsonObject();
            assertEquals("image", imageBlock.get("type").getAsString());
            assertEquals("base64", imageBlock.get("sourceKind").getAsString());
            assertTrue(imageBlock.get("src").getAsString().startsWith("data:image/png;base64,"));
            assertEquals(imagePath.toAbsolutePath().toString(), imageBlock.get("localPath").getAsString());
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("Analyze this image", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void normalizeClaudeHistoryMessageRestoresCliReadToolImagePrompt() throws IOException {
        Path imagePath = Files.createTempFile("claude-history-cli-read-image", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));
            String normalizedPath = imagePath.toAbsolutePath().toString().replace('\\', '/');

            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(createUserMessage("请描述图片内容\n\n" + "[Image #1: " + normalizedPath + "]\n" + "Use the Read tool to inspect this image file, then answer using its visible content: " + normalizedPath));

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            JsonObject imageBlock = contentBlocks.get(0).getAsJsonObject();
            assertEquals("image", imageBlock.get("type").getAsString());
            assertEquals(imagePath.toAbsolutePath().toString(), imageBlock.get("localPath").getAsString());
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("请描述图片内容", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void normalizeClaudeHistoryMessageStripsAppendedProjectModulesContext() throws IOException {
        Path imagePath = Files.createTempFile("claude-history-image-context", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(
                    createUserMessage("[Image #1: " + imagePath + "]\n\n"
                            + "The user has attached the image(s) above. Please use the Read tool to view them.\n\n"
                            + "用户原始描述\n\n"
                            + "## Project Modules\n\n"
                            + "This project contains multiple modules:\n"
                            + "- `idea-claude-code-gui`\n")
            );

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("用户原始描述", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void normalizeClaudeHistoryMessageDoesNotDuplicateExistingImageBlock() throws IOException {
        // SDK-mode writes persist both an `image` content block and an inline
        // "[Image #N: path]" text reference. The loader must NOT create a second
        // image block from that text, otherwise the same image renders twice.
        Path imagePath = Files.createTempFile("claude-history-image-sdk", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonObject originalMessage = createUserMessageWithImageAndTextRef(imagePath);
            JsonObject normalized = ClaudeSessionQueryService.normalizeClaudeHistoryMessage(originalMessage);

            JsonArray contentBlocks = normalized.getAsJsonObject("message").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            JsonObject preservedImage = contentBlocks.get(0).getAsJsonObject();
            assertEquals("image", preservedImage.get("type").getAsString());
            // Must be the original block (base64 source), NOT a regenerated resource_url block.
            assertTrue(preservedImage.has("source"));
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("请分析这张图片", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    private JsonObject createUserMessageWithImageAndTextRef(Path imagePath) {
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", "cG5nLWJ5dGVz");
        imageBlock.add("source", source);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", "[Image #1: " + imagePath + "]\n\n"
                + "The user has attached the image(s) above. Please use the Read tool to view them.\n\n"
                + "请分析这张图片");

        JsonArray content = new JsonArray();
        content.add(imageBlock);
        content.add(textBlock);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        raw.add("message", message);
        return raw;
    }

    private JsonObject createUserMessage(String text) {
        return createUserMessage(text, null);
    }

    private JsonObject createUserMessage(String text, String messageId) {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);

        JsonArray content = new JsonArray();
        content.add(textBlock);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        if (messageId != null) {
            raw.addProperty(CommonConstants.JSON_KEY_UUID, messageId);
        }
        raw.add("message", message);
        return raw;
    }

    private JsonObject createCheckpoint(String messageId, boolean nestedOnly) {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_FILE_HISTORY_SNAPSHOT);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty(CommonConstants.JSON_KEY_MESSAGE_ID, messageId);
        checkpoint.add(CommonConstants.JSON_KEY_SNAPSHOT, snapshot);
        if (!nestedOnly) {
            checkpoint.addProperty(CommonConstants.JSON_KEY_MESSAGE_ID, messageId);
        }
        return checkpoint;
    }
}
