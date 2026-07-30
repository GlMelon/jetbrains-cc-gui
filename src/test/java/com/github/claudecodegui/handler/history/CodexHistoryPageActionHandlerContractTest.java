package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.payload.CodexHistoryPageRequestPayloadField;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexHistoryPageActionHandlerContractTest {
    @Test
    public void paginationCursorIsRequiredByWireSchema() {
        assertFalse(CodexHistoryPageRequestPayloadField.BEFORE_TURN.optional());
    }

    @Test
    public void handlerUsesIdeExecutorEdtAndPrependMode() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/github/claudecodegui/handler/history/LoadCodexHistoryPageActionHandler.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("AppExecutorUtil.getAppExecutorService()"));
        assertTrue(source.contains("ApplicationManager.getApplication().invokeLater"));
        assertTrue(source.contains("beforeTurn is required"));
        assertTrue(source.contains("beforeTurn must be non-negative"));
        assertTrue(source.contains("CodexHistoryPageMode.PREPEND"));
    }
}
