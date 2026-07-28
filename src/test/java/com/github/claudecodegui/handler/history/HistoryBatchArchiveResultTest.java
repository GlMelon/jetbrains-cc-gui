package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryBatchArchiveResultTest {

    @Test
    public void successRequiresEveryRequestedSessionToBeArchived() {
        assertTrue(new HistoryBatchArchiveResult(List.of("s1", "s2"), List.of("s1", "s2")).success());
        assertFalse(new HistoryBatchArchiveResult(List.of("s1", "s2"), List.of("s1")).success());
        assertFalse(HistoryBatchArchiveResult.none(List.of()).success());
    }

    @Test
    public void failedSessionIdsPreserveRequestOrder() {
        HistoryBatchArchiveResult result = new HistoryBatchArchiveResult(
                List.of("s1", "s2", "s3"), List.of("s2"));

        assertEquals(List.of("s1", "s3"), result.failedSessionIds());
    }
}
