package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.provider.common.SessionHistoryPageSlicer;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.google.gson.JsonObject;

import java.util.List;

/** Backend SSOT for Codex persisted-history pagination. */
public class CodexHistoryPageService {
    private final CodexHistoryPageReader reader;
    private final int pageSize;

    public CodexHistoryPageService(CodexHistoryPageReader reader) {
        this(reader, CommonConstants.CODEX_HISTORY_PAGE_SIZE);
    }

    CodexHistoryPageService(CodexHistoryPageReader reader, int pageSize) {
        if (reader == null) {
            throw new IllegalArgumentException("reader is required");
        }
        this.reader = reader;
        this.pageSize = Math.max(1, pageSize);
    }

    public CodexHistoryPageResult loadInitialPage(String sessionId) {
        return slice(reader.readFrontendMessages(sessionId), sessionId, null, CodexHistoryPageMode.REPLACE);
    }

    public CodexHistoryPageResult loadEarlierPage(String sessionId, Integer beforeTurn) {
        return slice(reader.readFrontendMessages(sessionId), sessionId, beforeTurn, CodexHistoryPageMode.PREPEND);
    }

    CodexHistoryPageResult slice(List<JsonObject> allMessages, String sessionId, Integer beforeTurn,
                                 CodexHistoryPageMode mode) {
        // 切片语义单点在 SessionHistoryPageSlicer(Codex/Claude/纯 CLI 三方共用,消复制粘贴)。
        SessionHistoryLoadResult result =
                SessionHistoryPageSlicer.slice(allMessages, sessionId, beforeTurn, mode, pageSize);
        return new CodexHistoryPageResult(result.messages(), result.pageInfo());
    }

    static boolean isHumanUserMessage(JsonObject message) {
        return SessionHistoryPageSlicer.isHumanUserMessage(message);
    }
}
