package com.github.claudecodegui.cli.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * safePromptArg 行为测试:位置参数注入防御(前导 {@code -} flag 注入 / 前导 {@code @} 文件参数注入)。
 * 前导 @ 防护对称 ai-bridge marker-protocol.js(pi/omp parseArgs 把 @ 开头 token 归类为文件参数)。
 */
public class AbstractRunOnceSafePromptArgTest {

    @Test
    public void guardsLeadingDash() {
        assertEquals(" --flag", AbstractRunOnceCliSession.safePromptArg("--flag"));
    }

    @Test
    public void guardsLeadingAtMention() {
        assertEquals(" @file#L1 是什么", AbstractRunOnceCliSession.safePromptArg("@file#L1 是什么"));
    }

    @Test
    public void leavesPlainTextUnchanged() {
        assertEquals("普通消息", AbstractRunOnceCliSession.safePromptArg("普通消息"));
        assertEquals("mid @mention unchanged", AbstractRunOnceCliSession.safePromptArg("mid @mention unchanged"));
    }

    @Test
    public void nullNormalizesToEmpty() {
        assertEquals("", AbstractRunOnceCliSession.safePromptArg(null));
    }
}
