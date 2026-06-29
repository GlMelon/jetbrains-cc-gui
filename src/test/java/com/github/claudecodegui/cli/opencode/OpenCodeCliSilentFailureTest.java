package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSessionCallback;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 静默空失败判定验证(2026-06-29 opencode CLI「Generating response 后无输出无错误」修复)。
 * <p>
 * 根因:opencode.cmd → cmd.exe 包装下 getOutputStream().close() 不传播 stdin EOF → opencode
 * 阻塞读 stdin → exit0 无事件流 → runOnce 走 exit0 分支静默空完成。修复用 redirectInput(NUL)
 * 可靠关 stdin,并增加 {@link OpenCodeCliSession#isSilentEmptyFailure} 把"零事件 exit0"上报为错误。
 * <p>
 * 本测试覆盖判定逻辑(平台耦合的 runOnce 本身不在单测范围,与既有 OpenCodeCliSessionCommandTest 同范式)。
 */
public class OpenCodeCliSilentFailureTest {

    private static CliSessionCallback noopCallback() {
        return new CliSessionCallback() {
            @Override public void onMessage(String type, String content) { }
            @Override public void onError(String error) { }
            @Override public void onComplete(boolean success, String finalResult, String error) { }
        };
    }

    @Test
    public void freshParser_isSilentEmptyFailure() {
        // opencode 整轮未产出任何事件(exit0 无事件流):判为静默空失败,应上报错误
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(noopCallback());
        assertFalse("fresh parser 未收到事件", parser.receivedAnyEvent());
        assertTrue("零事件 exit0 应判为静默空失败", OpenCodeCliSession.isSilentEmptyFailure(parser));
    }

    @Test
    public void afterStepStartEvent_notSilentEmptyFailure() {
        // 收到 step_start(首轮事件):opencode 正常产出事件流,不再判为静默失败
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(noopCallback());
        parser.parseLine("{\"type\":\"step_start\",\"sessionID\":\"ses_abc\",\"part\":{}}");
        assertTrue(parser.receivedAnyEvent());
        assertEquals("ses_abc", parser.capturedSessionId());
        assertFalse(OpenCodeCliSession.isSilentEmptyFailure(parser));
    }

    @Test
    public void afterTextEvent_notSilentEmptyFailure() {
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(noopCallback());
        parser.parseLine("{\"type\":\"text\",\"sessionID\":\"ses_x\",\"part\":{\"text\":\"hello\"}}");
        assertTrue(parser.receivedAnyEvent());
        assertEquals("hello", parser.accumulatedText());
        assertFalse(OpenCodeCliSession.isSilentEmptyFailure(parser));
    }

    @Test
    public void nonJsonLinesDoNotCountAsEvents() {
        // 启动 banner / 错误噪声行(非 JSON)被会话层收集到 diagnostic,不进解析器,
        // 不应算作"事件"——这种场景仍是静默空失败
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(noopCallback());
        parser.parseLine("opencode v1.17.11");
        parser.parseLine("");
        parser.parseLine("   ");
        assertFalse(parser.receivedAnyEvent());
        assertTrue(OpenCodeCliSession.isSilentEmptyFailure(parser));
    }

    @Test
    public void resetForRetryClearsReceivedAnyEvent() {
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(noopCallback());
        parser.parseLine("{\"type\":\"step_start\",\"sessionID\":\"ses_abc\",\"part\":{}}");
        assertTrue(parser.receivedAnyEvent());
        parser.resetForRetry();
        assertFalse("重试应重置事件标志", parser.receivedAnyEvent());
        assertTrue(OpenCodeCliSession.isSilentEmptyFailure(parser));
    }
}
