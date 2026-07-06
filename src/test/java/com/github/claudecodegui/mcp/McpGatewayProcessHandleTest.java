package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.PlatformUtils;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * McpGatewayProcessHandle 自愈机制测试。
 * <p>核心 {@link McpGatewayProcessHandle#isRestartStorm} 是纯函数(注入时间戳列表 + now),
 * 必跑(风暴判定是放弃自愈的决策点)。{@code simulateExitForTests} 用真实长命进程验证
 * callback 触发/屏蔽(不依赖真实退出);真实 onExit 端到端集成留手测(platform 耦合)。
 */
public class McpGatewayProcessHandleTest {

    @Test
    public void isRestartStormReturnsFalseForEmptyOrNullList() {
        assertFalse(McpGatewayProcessHandle.isRestartStorm(List.of(), 100_000L, 3, 30_000));
        assertFalse(McpGatewayProcessHandle.isRestartStorm(null, 100_000L, 3, 30_000));
    }

    @Test
    public void isRestartStormReturnsFalseAtThreshold() {
        // 3 次 exit(等于 threshold),count > 3 为 false(阈值是严格大于,等于不算风暴)
        assertFalse(McpGatewayProcessHandle.isRestartStorm(List.of(90_000L, 95_000L, 99_000L), 100_000L, 3, 30_000));
    }

    @Test
    public void isRestartStormReturnsTrueAboveThreshold() {
        // 4 次 exit 在窗口内 > 3,风暴
        assertTrue(McpGatewayProcessHandle.isRestartStorm(
                List.of(90_000L, 92_000L, 95_000L, 99_000L), 100_000L, 3, 30_000));
    }

    @Test
    public void isRestartStormIgnoresExitsOutsideWindow() {
        // 窗口外(>30s 前)2 次 + 窗口内 2 次,threshold=3,窗口内 count=2 false
        assertFalse(McpGatewayProcessHandle.isRestartStorm(
                List.of(10_000L, 20_000L, 90_000L, 99_000L), 100_000L, 3, 30_000));
    }

    @Test
    public void simulateExitInvokesRegisteredCallback() throws Exception {
        McpGatewayProcessHandle handle = McpGatewayProcessHandle.start(longLivedCommand());
        try {
            boolean[] called = {false};
            handle.setOnExitCallback(() -> called[0] = true);
            handle.simulateExitForTests();
            assertTrue("simulateExit 应触发已注册回调", called[0]);
        } finally {
            handle.stop();
        }
    }

    @Test
    public void simulateExitSkipsWhenCallbackCleared() throws Exception {
        // stop 前置 null:simulateExit 不应触发回调(防主动停止误触发自愈的核心语义)
        McpGatewayProcessHandle handle = McpGatewayProcessHandle.start(longLivedCommand());
        try {
            boolean[] called = {false};
            handle.setOnExitCallback(() -> called[0] = true);
            handle.setOnExitCallback(null);
            handle.simulateExitForTests();
            assertFalse("callback 已清,不应触发", called[0]);
        } finally {
            handle.stop();
        }
    }

    /** 存活 ~30s 的命令,构造 handle 后立即 simulateExit 不依赖真实退出。 */
    private static List<String> longLivedCommand() {
        return PlatformUtils.isWindows()
                ? List.of("cmd", "/c", "ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
    }
}
