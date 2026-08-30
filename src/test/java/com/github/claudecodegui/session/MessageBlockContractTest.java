package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.MessageBlockToolIdSource;
import com.github.claudecodegui.protocol.MessageBlockToolStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MessageBlockContractTest {

    @Test
    public void missingToolUseIdIsGeneratedStablyFromIdentitySeed() {
        JsonObject first = MessageBlockContract.normalizeToolUse(toolUse(null, "search", "q"), "history-1");
        JsonObject second = MessageBlockContract.normalizeToolUse(toolUse(null, "search", "q"), "history-1");

        assertEquals(first.get(CommonConstants.JSON_KEY_ID), second.get(CommonConstants.JSON_KEY_ID));
        assertTrue(first.get(CommonConstants.JSON_KEY_ID).getAsString().startsWith("tool-use-"));
        assertEquals(MessageBlockToolIdSource.GENERATED.value(),
                first.get(MessageBlockContract.KEY_TOOL_ID_SOURCE).getAsString());
        assertEquals(MessageBlockToolStatus.PENDING.value(),
                first.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertFalse(first.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void differentIdentitySeedsDoNotCollapseIdenticalToolUses() {
        JsonObject first = MessageBlockContract.normalizeToolUse(toolUse(null, "search", "q"), "history-1");
        JsonObject second = MessageBlockContract.normalizeToolUse(toolUse(null, "search", "q"), "history-2");

        assertNotEquals(first.get(CommonConstants.JSON_KEY_ID), second.get(CommonConstants.JSON_KEY_ID));
    }

    @Test
    public void synchronizationUpdatesDeepCopiedToolUseAfterResult() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        JsonObject use = ledger.normalizeToolUse(toolUse("call-1", "search", "q"), "live-1");
        JsonObject envelope = assistantMessageWithRaw(rawWithBlocks(use.deepCopy()));

        JsonObject result = ledger.normalizeToolResult(result("call-1", "ok", false));
        ledger.synchronizeEnvelope(envelope);

        JsonObject synchronizedUse = rawBlocks(envelope).get(0).getAsJsonObject();
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                synchronizedUse.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(synchronizedUse.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                result.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(result.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void liveResultBeforeToolUseEventuallyPairsBothBlocks() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        JsonObject result = ledger.normalizeToolResult(result("call-1", "ok", false));
        JsonObject resultEnvelope = userMessage(result.deepCopy());

        JsonObject use = ledger.normalizeToolUse(toolUse("call-1", "search", "q"), "live-1");
        JsonObject useEnvelope = assistantMessageWithRaw(rawWithBlocks(use.deepCopy()));
        ledger.synchronizeEnvelope(resultEnvelope);
        ledger.synchronizeEnvelope(useEnvelope);

        JsonObject synchronizedResult = rawBlocks(resultEnvelope).get(0).getAsJsonObject();
        JsonObject synchronizedUse = rawBlocks(useEnvelope).get(0).getAsJsonObject();
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                synchronizedResult.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(synchronizedResult.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                synchronizedUse.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(synchronizedUse.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void liveParallelToolResultsRemainIsolatedWhenArrivingOutOfOrder() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        JsonObject firstUse = ledger.normalizeToolUse(toolUse("call-1", "search", "q1"), "live-1");
        JsonObject secondUse = ledger.normalizeToolUse(toolUse("call-2", "search", "q2"), "live-2");
        JsonObject useEnvelope = assistantMessageWithRaw(rawWithBlocks(
                firstUse.deepCopy(), secondUse.deepCopy()));

        ledger.normalizeToolResult(result("call-2", "second", false));
        ledger.normalizeToolResult(result("call-1", "first", false));
        ledger.synchronizeEnvelope(useEnvelope);

        JsonArray synchronizedUses = rawBlocks(useEnvelope);
        assertEquals(MessageBlockToolStatus.COMPLETED.value(), synchronizedUses.get(0).getAsJsonObject()
                .get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(synchronizedUses.get(0).getAsJsonObject()
                .get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(), synchronizedUses.get(1).getAsJsonObject()
                .get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(synchronizedUses.get(1).getAsJsonObject()
                .get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void orphanAndDuplicateResultsHaveExplicitStatus() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        ledger.normalizeToolUse(toolUse("call-1", "search", "q"), "live-1");

        JsonObject orphan = ledger.normalizeToolResult(result("missing", "not found", true));
        JsonObject first = ledger.normalizeToolResult(result("call-1", "ok", false));
        JsonObject duplicate = ledger.normalizeToolResult(result("call-1", "ok again", false));

        assertEquals(MessageBlockToolStatus.ORPHANED.value(),
                orphan.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertFalse(orphan.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                first.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertEquals(MessageBlockToolStatus.DUPLICATE.value(),
                duplicate.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
    }

    @Test
    public void endOfStreamMarksDeepCopiedUnresolvedToolUseUnpaired() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        JsonObject use = ledger.normalizeToolUse(toolUse("call-1", "search", "q"), "live-1");
        JsonObject envelope = assistantMessageWithRaw(rawWithBlocks(use.deepCopy()));

        ledger.markUnpairedToolUses();
        ledger.synchronizeEnvelope(envelope);
        ledger.markUnpairedToolUses();
        ledger.synchronizeEnvelope(envelope);

        JsonObject synchronizedUse = rawBlocks(envelope).get(0).getAsJsonObject();
        assertEquals(MessageBlockToolStatus.UNPAIRED.value(),
                synchronizedUse.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertFalse(synchronizedUse.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void missingResultIdPairsOnlyPendingToolUse() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        ledger.normalizeToolUse(toolUse("call-1", "search", "q"), "live-1");

        JsonObject normalized = ledger.normalizeToolResult(result(null, "ok", false));

        assertEquals("call-1", normalized.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString());
        assertEquals(MessageBlockToolIdSource.GENERATED.value(),
                normalized.get(MessageBlockContract.KEY_TOOL_ID_SOURCE).getAsString());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                normalized.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(normalized.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void missingResultIdWithMultiplePendingUsesRemainsOrphaned() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        ledger.normalizeToolUse(toolUse("call-1", "search", "q1"), "live-1");
        ledger.normalizeToolUse(toolUse("call-2", "search", "q2"), "live-2");

        JsonObject normalized = ledger.normalizeToolResult(result(null, "ok", false));

        assertTrue(normalized.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString()
                .startsWith("tool-result-"));
        assertEquals(MessageBlockToolStatus.ORPHANED.value(),
                normalized.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertFalse(normalized.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void historyPairsResultThatAppearsBeforeToolUseAndPreservesBlockOrder() {
        JsonObject resultMessage = userMessage(result("call-1", "ok", false));
        JsonObject assistantMessage = assistantMessage(
                text("before"),
                thinking("reason"),
                toolUse("call-1", "search", "q"),
                text("after"));

        List<JsonObject> normalized = MessageBlockContract.normalizeHistoryMessages(
                List.of(resultMessage, assistantMessage));

        JsonArray blocks = rawBlocks(normalized.get(1));
        JsonObject resultBlock = rawBlocks(normalized.get(0)).get(0).getAsJsonObject();

        assertEquals(4, blocks.size());
        assertEquals(CommonConstants.BLOCK_TYPE_TEXT, blockType(blocks, 0));
        assertEquals(CommonConstants.BLOCK_TYPE_THINKING, blockType(blocks, 1));
        assertEquals(CommonConstants.BLOCK_TYPE_TOOL_USE, blockType(blocks, 2));
        assertEquals(CommonConstants.BLOCK_TYPE_TEXT, blockType(blocks, 3));
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                blocks.get(2).getAsJsonObject().get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertTrue(resultBlock.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void sequentialMissingResultIdsPairWithThePendingUseAtThatPoint() {
        JsonObject firstUse = assistantMessage(toolUse("call-1", "search", "q1"));
        JsonObject firstResult = userMessage(result(null, "first", false));
        JsonObject secondUse = assistantMessage(toolUse("call-2", "search", "q2"));
        JsonObject secondResult = userMessage(result(null, "second", false));

        List<JsonObject> normalized = MessageBlockContract.normalizeHistoryMessages(
                List.of(firstUse, firstResult, secondUse, secondResult));

        JsonObject normalizedFirstResult = rawBlocks(normalized.get(1)).get(0).getAsJsonObject();
        JsonObject normalizedSecondResult = rawBlocks(normalized.get(3)).get(0).getAsJsonObject();
        assertEquals("call-1", normalizedFirstResult.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString());
        assertEquals("call-2", normalizedSecondResult.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString());
        assertTrue(normalizedFirstResult.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
        assertTrue(normalizedSecondResult.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    @Test
    public void parallelToolResultsCanArriveOutOfOrder() {
        JsonObject uses = assistantMessage(
                toolUse("call-1", "search", "q1"),
                toolUse("call-2", "search", "q2"));
        JsonObject secondResult = userMessage(result("call-2", "second", false));
        JsonObject firstResult = userMessage(result("call-1", "first", false));

        List<JsonObject> normalized = MessageBlockContract.normalizeHistoryMessages(
                List.of(uses, secondResult, firstResult));

        JsonArray blocks = rawBlocks(normalized.get(0));
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                blocks.get(0).getAsJsonObject().get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                blocks.get(1).getAsJsonObject().get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
    }

    @Test
    public void onlyAuthoritativeContentContainerIsNormalized() {
        JsonObject mirrored = assistantMessage(toolUse("call-1", "search", "q"));
        mirrored.add(CommonConstants.JSON_KEY_CONTENT, rawBlocks(mirrored).deepCopy());

        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();
        JsonObject normalized = MessageBlockContract.normalizeEnvelope(mirrored, ledger);

        assertEquals(1, ledger.toolUseIds().size());
        assertNotNull(rawBlocks(normalized).get(0).getAsJsonObject()
                .get(MessageBlockContract.KEY_TOOL_STATUS));
        assertFalse(normalized.getAsJsonArray(CommonConstants.JSON_KEY_CONTENT).get(0)
                .getAsJsonObject().has(MessageBlockContract.KEY_TOOL_STATUS));
    }

    @Test
    public void malformedContentBlockDoesNotPreventOtherBlocksFromBeingNormalized() {
        JsonArray content = new JsonArray();
        content.add("malformed");
        content.add(toolUse("call-1", "search", "q"));
        content.add(result("call-1", "ok", false));
        JsonObject message = new JsonObject();
        message.add(CommonConstants.JSON_KEY_CONTENT, content);

        List<JsonObject> normalized = MessageBlockContract.normalizeHistoryMessages(
                List.of(assistantMessageWithRaw(message)));
        JsonArray blocks = rawBlocks(normalized.get(0));

        assertEquals(3, blocks.size());
        assertEquals("malformed", blocks.get(0).getAsString());
        assertNotNull(blocks.get(1).getAsJsonObject().get(CommonConstants.JSON_KEY_ID));
        assertEquals(MessageBlockToolStatus.COMPLETED.value(),
                blocks.get(1).getAsJsonObject().get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
    }

    @Test
    public void identicalOrphanResultsReceiveDistinctStableIdentities() {
        MessageBlockContract.ToolLedger ledger = new MessageBlockContract.ToolLedger();

        JsonObject first = ledger.normalizeToolResult(result(null, "ok", false));
        JsonObject second = ledger.normalizeToolResult(result(null, "ok", false));

        String firstId = first.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString();
        String secondId = second.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString();
        assertFalse(firstId.equals(secondId));
    }

    @Test
    public void missingResultIdWithoutPendingUseGetsExplicitOrphanIdentity() {
        JsonObject normalized = MessageBlockContract.normalizeToolResult(result(null, "ok", false), List.of());

        assertNotNull(normalized.get(CommonConstants.JSON_KEY_TOOL_USE_ID));
        assertTrue(normalized.get(CommonConstants.JSON_KEY_TOOL_USE_ID).getAsString()
                .startsWith("tool-result-"));
        assertEquals(MessageBlockToolStatus.ORPHANED.value(),
                normalized.get(MessageBlockContract.KEY_TOOL_STATUS).getAsString());
        assertFalse(normalized.get(MessageBlockContract.KEY_PAIRED).getAsBoolean());
    }

    private static String blockType(JsonArray blocks, int index) {
        return blocks.get(index).getAsJsonObject().get(CommonConstants.JSON_KEY_TYPE).getAsString();
    }

    private static JsonArray rawBlocks(JsonObject message) {
        return message.getAsJsonObject(CommonConstants.JSON_KEY_RAW)
                .getAsJsonArray(CommonConstants.JSON_KEY_CONTENT);
    }

    private static JsonObject toolUse(String id, String name, String query) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_USE);
        if (id != null) {
            block.addProperty(CommonConstants.JSON_KEY_ID, id);
        }
        block.addProperty(CommonConstants.JSON_KEY_NAME, name);
        JsonObject input = new JsonObject();
        input.addProperty("query", query);
        block.add(CommonConstants.JSON_KEY_INPUT, input);
        return block;
    }

    private static JsonObject result(String id, String content, boolean error) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_RESULT);
        if (id != null) {
            block.addProperty(CommonConstants.JSON_KEY_TOOL_USE_ID, id);
        }
        block.addProperty(CommonConstants.JSON_KEY_CONTENT, content);
        block.addProperty(CommonConstants.JSON_KEY_IS_ERROR, error);
        return block;
    }

    private static JsonObject text(String value) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TEXT);
        block.addProperty(CommonConstants.JSON_KEY_TEXT, value);
        return block;
    }

    private static JsonObject thinking(String value) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_THINKING);
        block.addProperty(CommonConstants.JSON_KEY_THINKING, value);
        return block;
    }

    private static JsonObject assistantMessage(JsonObject... blocks) {
        JsonObject front = new JsonObject();
        front.addProperty(CommonConstants.JSON_KEY_TYPE, "assistant");
        front.addProperty(CommonConstants.JSON_KEY_CONTENT, "");
        front.add(CommonConstants.JSON_KEY_RAW, rawWithBlocks(blocks));
        return front;
    }

    private static JsonObject userMessage(JsonObject block) {
        JsonObject front = new JsonObject();
        front.addProperty(CommonConstants.JSON_KEY_TYPE, "user");
        front.addProperty(CommonConstants.JSON_KEY_CONTENT, "");
        front.add(CommonConstants.JSON_KEY_RAW, rawWithBlocks(block));
        return front;
    }

    private static JsonObject rawWithBlocks(JsonObject... blocks) {
        JsonArray content = new JsonArray();
        for (JsonObject block : blocks) {
            content.add(block);
        }
        JsonObject raw = new JsonObject();
        raw.addProperty(CommonConstants.JSON_KEY_ROLE, "assistant");
        raw.add(CommonConstants.JSON_KEY_CONTENT, content);
        return raw;
    }

    private static JsonObject assistantMessageWithRaw(JsonObject rawMessage) {
        JsonObject front = new JsonObject();
        front.addProperty(CommonConstants.JSON_KEY_TYPE, "assistant");
        front.addProperty(CommonConstants.JSON_KEY_CONTENT, "");
        front.add(CommonConstants.JSON_KEY_RAW, rawMessage);
        return front;
    }
}
