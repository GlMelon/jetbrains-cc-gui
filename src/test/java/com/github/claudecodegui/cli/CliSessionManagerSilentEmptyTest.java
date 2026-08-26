package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 验证 {@link CliSessionManager#adapt} 的通用"静默空成功"检测:
 * CLI provider 进程 exit0 且无 error,但整轮未产出任何内容类消息时,
 * 应降级为错误上报,而非假装成功(否则前端只显示完成提示却无正文)。
 */
public class CliSessionManagerSilentEmptyTest {

    /** 记录 MessageCallback 收到的回调,便于断言。 */
    private static final class CapturingCallback implements MessageCallback {
        final List<String> messageTypes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<CliResult> completions = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messageTypes.add(type);
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }

        @Override
        public void onComplete(CliResult result) {
            completions.add(result);
        }
    }

    // ── isContentBearing 纯函数判定 ──────────────────────────────────────

    @Test
    public void isContentBearing_controlTypesAreNotContent() {
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_SESSION_ID));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_STREAM_START));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_STREAM_END));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_MESSAGE_START));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_MESSAGE_END));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_BLOCK_RESET));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_RESULT));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.MSG_USAGE));
        assertFalse(CliSessionManager.isContentBearing(CliConstants.CODEX_MSG_STATUS));
    }

    @Test
    public void isContentBearing_contentTypesAreContent() {
        assertTrue(CliSessionManager.isContentBearing(CliConstants.MSG_CONTENT_DELTA));
        assertTrue(CliSessionManager.isContentBearing(CliConstants.MSG_THINKING_DELTA));
        assertTrue(CliSessionManager.isContentBearing(CliConstants.MSG_CONTENT));
        assertTrue(CliSessionManager.isContentBearing(CommonConstants.MSG_TYPE_TOOL_USE));
        assertTrue(CliSessionManager.isContentBearing(CommonConstants.MSG_TYPE_TOOL_RESULT));
        assertTrue(CliSessionManager.isContentBearing(CommonConstants.MSG_TYPE_TEXT));
        assertTrue(CliSessionManager.isContentBearing(CommonConstants.MSG_TYPE_THINKING));
        assertTrue(CliSessionManager.isContentBearing(CommonConstants.MSG_TYPE_ASSISTANT));
    }

    @Test
    public void isContentBearing_nullAndUnknownAreSafe() {
        // null 不算内容(保守,避免空内容误判为有产出)
        assertFalse(CliSessionManager.isContentBearing(null));
        // 未知类型默认算内容(黑名单方向:宁可漏报空失败,不误伤合法回合)
        assertTrue(CliSessionManager.isContentBearing("some_future_content_type"));
    }

    // ── adapt 静默空成功降级 ──────────────────────────────────────────────

    @Test
    public void adapt_silentEmptySuccess_controlEventsOnly_downgradesToError() {
        // 复现 opencode bug 事件流:只有 stream_start + session_id + result(usage),无任何文本
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "opencode");

        cb.onMessage(CliConstants.MSG_STREAM_START, "");
        cb.onMessage(CliConstants.MSG_SESSION_ID, "ses_xxx");
        cb.onMessage(CliConstants.MSG_RESULT, "{\"usage\":{}}");
        cb.onComplete(true, "", null);

        assertEquals("应上报降级错误", 1, sink.errors.size());
        assertTrue("诊断信息应说明无内容", sink.errors.get(0).contains("未返回任何内容"));
        assertEquals(1, sink.completions.size());
        assertFalse("应降级为失败", sink.completions.get(0).success);
        assertNotNull(sink.completions.get(0).error);
    }

    @Test
    public void adapt_noMessagesAtAll_downgradesToError() {
        // 完全无 onMessage 的静默空成功(如子进程阻塞读 stdin 后 exit0)
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "codex");

        cb.onComplete(true, "", null);

        assertEquals(1, sink.errors.size());
        assertFalse(sink.completions.get(0).success);
    }

    @Test
    public void adapt_textContent_notDowngraded() {
        // 正常文本回合不误伤
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "claude");

        cb.onMessage(CliConstants.MSG_STREAM_START, "");
        cb.onMessage(CliConstants.MSG_CONTENT_DELTA, "你好");
        cb.onComplete(true, "你好", null);

        assertTrue("正常文本回合不应上报错误", sink.errors.isEmpty());
        assertTrue(sink.completions.get(0).success);
    }

    @Test
    public void adapt_toolUseOnly_notDowngraded() {
        // 纯工具调用回合不误伤(无文本但有工具产出,前端会渲染工具卡片)
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "codex");

        cb.onMessage(CommonConstants.MSG_TYPE_TOOL_USE, "{\"name\":\"bash\"}");
        cb.onMessage(CommonConstants.MSG_TYPE_TOOL_RESULT, "{}");
        cb.onComplete(true, "", null);

        assertTrue("纯工具回合不应误判为空成功", sink.errors.isEmpty());
        assertTrue(sink.completions.get(0).success);
    }

    @Test
    public void adapt_thinkingOnly_notDowngraded() {
        // 纯思考回合不误伤
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "opencode");

        cb.onMessage(CliConstants.MSG_THINKING_DELTA, "正在思考");
        cb.onComplete(true, "", null);

        assertTrue(sink.errors.isEmpty());
        assertTrue(sink.completions.get(0).success);
    }

    @Test
    public void adapt_explicitFailure_notDowngraded_passthrough() {
        // 已有 error 的失败回合:照常透传,不触发降级
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "claude");

        cb.onComplete(false, "", "网络错误");

        assertTrue("success=false 路径不应额外注入降级错误", sink.errors.isEmpty());
        assertEquals(1, sink.completions.size());
        assertFalse(sink.completions.get(0).success);
        assertEquals("网络错误", sink.completions.get(0).error);
    }

    @Test
    public void adapt_successWithExplicitError_notDowngraded() {
        // success=true 但已带 error(异常成功):不触发降级(已有错误信息,无需再注入)
        CapturingCallback sink = new CapturingCallback();
        CliSessionCallback cb = CliSessionManager.adapt(sink, "claude");

        cb.onComplete(true, "", "已有错误");

        assertTrue(sink.errors.isEmpty());
        assertTrue(sink.completions.get(0).success);
        assertEquals("已有错误", sink.completions.get(0).error);
    }
}
