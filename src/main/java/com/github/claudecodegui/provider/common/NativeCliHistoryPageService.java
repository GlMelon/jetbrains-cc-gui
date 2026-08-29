package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 纯 CLI provider(grok / kimi / pi)共用的磁盘历史分页服务。
 *
 * <p>模式照 {@code ClaudeHistoryPageService}:reader 函数式注入保持可测性与
 * NodeService 解耦;切片语义单点委托 {@link SessionHistoryPageSlicer}。各家差异只在
 * reader 定位方式(grok/kimi 按 session 目录、pi 按会话文件),由
 * {@code SessionProviderRouter} 装配时注入。翻页请求经
 * {@code LoadCodexHistoryPageActionHandler} 路由(白名单含三家)。
 */
public class NativeCliHistoryPageService {

    /** 前端格式历史消息读取器(sessionId + cwd → messages),注入以便单测与磁盘解耦。 */
    public interface HistoryMessageReader {
        List<JsonObject> readFrontendMessages(String sessionId, String cwd);
    }

    private final HistoryMessageReader reader;
    private final int pageSize;

    public NativeCliHistoryPageService(HistoryMessageReader reader) {
        this(reader, CommonConstants.NATIVE_CLI_HISTORY_PAGE_SIZE);
    }

    NativeCliHistoryPageService(HistoryMessageReader reader, int pageSize) {
        if (reader == null) {
            throw new IllegalArgumentException("reader is required");
        }
        this.reader = reader;
        this.pageSize = Math.max(1, pageSize);
    }

    /** 初始页(REPLACE):最近 pageSize 轮 + 分页元数据。 */
    public SessionHistoryLoadResult loadInitialPage(String sessionId, String cwd) {
        return slice(reader.readFrontendMessages(sessionId, cwd), sessionId, null, CodexHistoryPageMode.REPLACE);
    }

    /** 更早页(PREPEND):beforeTurn 之前的 pageSize 轮。 */
    public SessionHistoryLoadResult loadEarlierPage(String sessionId, String cwd, Integer beforeTurn) {
        return slice(reader.readFrontendMessages(sessionId, cwd), sessionId, beforeTurn, CodexHistoryPageMode.PREPEND);
    }

    SessionHistoryLoadResult slice(List<JsonObject> allMessages, String sessionId, Integer beforeTurn,
                                   CodexHistoryPageMode mode) {
        return SessionHistoryPageSlicer.slice(allMessages, sessionId, beforeTurn, mode, pageSize);
    }
}
