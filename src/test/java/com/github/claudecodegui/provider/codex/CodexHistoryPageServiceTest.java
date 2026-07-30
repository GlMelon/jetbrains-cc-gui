package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.protocol.payload.CodexHistoryPageInfoPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexHistoryPageServiceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void initialPageLoadsOnlyLastTurnsAndKeepsTurnBoundaries() throws Exception {
        CodexHistoryPageService service = serviceWithPageSize(3);
        List<JsonObject> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(user("u" + i));
            messages.add(assistant("a" + i));
        }

        CodexHistoryPageResult result = service.slice(messages, "session-1", null, CodexHistoryPageMode.REPLACE);
        JsonObject info = result.pageInfo();

        assertEquals(6, result.messages().size());
        assertEquals("u2", result.messages().get(0).get(CommonConstants.JSON_KEY_CONTENT).getAsString());
        assertEquals("a4", result.messages().get(5).get(CommonConstants.JSON_KEY_CONTENT).getAsString());
        assertEquals(2, info.get(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey()).getAsInt());
        assertEquals(5, info.get(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey()).getAsInt());
        assertEquals(5, info.get(CodexHistoryPageInfoPayloadField.TOTAL_TURNS.wireKey()).getAsInt());
        assertTrue(info.get(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey()).getAsBoolean());
        assertEquals(CodexHistoryPageMode.REPLACE.value(), info.get(CodexHistoryPageInfoPayloadField.MODE.wireKey()).getAsString());
    }

    @Test
    public void earlierPagePrependsPreviousTurnsAndReportsCursorReset() throws Exception {
        CodexHistoryPageService service = serviceWithPageSize(3);
        List<JsonObject> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(user("u" + i));
            messages.add(assistant("a" + i));
        }

        CodexHistoryPageResult result = service.slice(messages, "session-1", 99, CodexHistoryPageMode.PREPEND);
        JsonObject info = result.pageInfo();

        assertEquals("u2", result.messages().get(0).get(CommonConstants.JSON_KEY_CONTENT).getAsString());
        assertEquals(2, info.get(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey()).getAsInt());
        assertEquals(5, info.get(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey()).getAsInt());
        assertTrue(info.get(CodexHistoryPageInfoPayloadField.CURSOR_RESET.wireKey()).getAsBoolean());
        assertEquals(CodexHistoryPageMode.PREPEND.value(), info.get(CodexHistoryPageInfoPayloadField.MODE.wireKey()).getAsString());
    }

    @Test
    public void zeroCursorReturnsEmptyPageWithoutLeadingTransportMessages() throws Exception {
        CodexHistoryPageService service = serviceWithPageSize(3);
        List<JsonObject> messages = new ArrayList<>();
        messages.add(assistant("leading transport"));
        messages.add(user("u0"));
        messages.add(assistant("a0"));

        CodexHistoryPageResult result = service.slice(messages, "session-1", 0, CodexHistoryPageMode.PREPEND);
        JsonObject info = result.pageInfo();

        assertTrue(result.messages().isEmpty());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey()).getAsInt());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey()).getAsInt());
        assertFalse(info.get(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey()).getAsBoolean());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.LOADED_MESSAGE_COUNT.wireKey()).getAsInt());
    }

    @Test
    public void initialPageWithoutHumanTurnsReturnsTransportMessages() throws Exception {
        CodexHistoryPageService service = serviceWithPageSize(3);
        List<JsonObject> messages = new ArrayList<>();
        messages.add(assistant("tool use"));
        messages.add(user(CommonConstants.TOOL_RESULT_PLACEHOLDER));

        CodexHistoryPageResult result = service.slice(messages, "session-1", null, CodexHistoryPageMode.REPLACE);
        JsonObject info = result.pageInfo();

        assertEquals(2, result.messages().size());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey()).getAsInt());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey()).getAsInt());
        assertEquals(0, info.get(CodexHistoryPageInfoPayloadField.TOTAL_TURNS.wireKey()).getAsInt());
        assertFalse(info.get(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey()).getAsBoolean());
        assertEquals(2, info.get(CodexHistoryPageInfoPayloadField.LOADED_MESSAGE_COUNT.wireKey()).getAsInt());
    }

    @Test
    public void humanUserDetectionIgnoresToolResultPlaceholders() {
        assertFalse(CodexHistoryPageService.isHumanUserMessage(user(CommonConstants.TOOL_RESULT_PLACEHOLDER)));
        assertTrue(CodexHistoryPageService.isHumanUserMessage(userWithRawBlock(CommonConstants.BLOCK_TYPE_IMAGE)));
    }

    private CodexHistoryPageService serviceWithPageSize(int pageSize) throws Exception {
        CodexHistoryReader reader = new CodexHistoryReader(temporaryFolder.newFolder().toPath(), GsonHolder.GSON);
        return new CodexHistoryPageService(new CodexHistoryPageReader(reader), pageSize);
    }

    private static JsonObject user(String content) {
        JsonObject message = new JsonObject();
        message.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_USER);
        message.addProperty(CommonConstants.JSON_KEY_CONTENT, content);
        return message;
    }

    private static JsonObject assistant(String content) {
        JsonObject message = new JsonObject();
        message.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.MSG_TYPE_ASSISTANT);
        message.addProperty(CommonConstants.JSON_KEY_CONTENT, content);
        return message;
    }

    private static JsonObject userWithRawBlock(String blockType) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, blockType);
        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject raw = new JsonObject();
        raw.add(CommonConstants.JSON_KEY_CONTENT, content);
        JsonObject message = user("");
        message.add(CommonConstants.JSON_KEY_RAW, raw);
        return message;
    }
}
