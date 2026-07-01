package com.github.claudecodegui.handler.history;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * OpenCodeHistoryProviderAdapter.loadSessionsJson 的归一化行为。
 * <p>
 * getSessionList 失败时返回 ""(bridge 未就绪/db 缺失/超时/子进程异常)。adapter 必须把空串归一化为
 * 合法空会话 JSON,避免 HistoryLoadService.enhanceHistoryWithFavorites → fromJson("")=null → 前端
 * JSON.parse("") 抛"解析历史数据失败"(对称 Codex/Claude reader 始终返回合法 JSON)。
 * <p>
 * 归一化抽为 package-private static 纯函数,无需构造 HandlerContext(具体类,Platform 依赖)。
 */
public class OpenCodeHistoryProviderAdapterTest {

    @Test
    public void normalizeSessionsJsonPassthroughValidJson() {
        String valid = "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\"}]}";
        assertEquals(valid, OpenCodeHistoryProviderAdapter.normalizeSessionsJson(valid));
    }

    @Test
    public void normalizeSessionsJsonBlankReturnsEmptySessionsContract() {
        assertEquals("{\"success\":true,\"sessions\":[]}",
                OpenCodeHistoryProviderAdapter.normalizeSessionsJson(""));
    }

    @Test
    public void normalizeSessionsJsonNullReturnsEmptySessionsContract() {
        assertEquals("{\"success\":true,\"sessions\":[]}",
                OpenCodeHistoryProviderAdapter.normalizeSessionsJson(null));
    }
}
