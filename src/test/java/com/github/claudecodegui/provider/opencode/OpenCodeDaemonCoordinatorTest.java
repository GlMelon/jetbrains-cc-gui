package com.github.claudecodegui.provider.opencode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * OpenCodeDaemonCoordinator 端口解析验证。
 * <p>
 * serve 改用 {@code --port 0}(系统分配空闲端口)后,实际端口只能从 serve stdout 的
 * "opencode server listening on http://host:port" 行解析。此纯函数是端口冲突根治的关键,
 * 单独 TDD 覆盖(Platform 耦合的 startServer 由集成/对照实验覆盖)。
 */
public class OpenCodeDaemonCoordinatorTest {

    @Test
    public void parseServingPort_extractsPortFromLoopbackListeningLine() {
        Integer port = OpenCodeDaemonCoordinator.parseServingPort(
                "opencode server listening on http://127.0.0.1:10436");
        assertEquals(Integer.valueOf(10436), port);
    }

    @Test
    public void parseServingPort_extractsPortFromWildcardHost() {
        Integer port = OpenCodeDaemonCoordinator.parseServingPort(
                "opencode server listening on http://0.0.0.0:4096");
        assertEquals(Integer.valueOf(4096), port);
    }

    @Test
    public void parseServingPort_returnsNullForNonListeningLine() {
        assertNull(OpenCodeDaemonCoordinator.parseServingPort("some unrelated stderr noise"));
    }

    @Test
    public void parseServingPort_returnsNullForNullOrEmpty() {
        assertNull(OpenCodeDaemonCoordinator.parseServingPort(null));
        assertNull(OpenCodeDaemonCoordinator.parseServingPort(""));
    }

    @Test
    public void parseServingPort_toleratesLeadingAnsiAndWarningLines() {
        // serve 启动时常先打印 password 警告,listening 行可能带 ANSI 控制序列
        Integer port = OpenCodeDaemonCoordinator.parseServingPort(
                "[0mopencode server listening on http://127.0.0.1:15000");
        assertEquals(Integer.valueOf(15000), port);
    }
}
