package com.github.claudecodegui.handler.clipboard;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约测试:ReadClipboardActionHandler 必须绑定 READ_CLIPBOARD 枚举并以 String 为原始载荷类型。
 * FrontendActionDispatcher 据此 O(1) 路由,绕开旧 ClipboardHandler 字符串 switch(AGENTS.md §2)。
 */
public class ReadClipboardActionHandlerTest {

    @Test
    public void bindsReadClipboardUpstreamActionWithRawStringPayload() {
        ReadClipboardActionHandler handler = new ReadClipboardActionHandler();
        assertEquals(UpstreamAction.READ_CLIPBOARD, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
