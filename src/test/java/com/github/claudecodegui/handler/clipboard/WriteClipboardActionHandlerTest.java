package com.github.claudecodegui.handler.clipboard;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约测试:WriteClipboardActionHandler 必须绑定 WRITE_CLIPBOARD 枚举并以 String 为原始载荷类型。
 */
public class WriteClipboardActionHandlerTest {

    @Test
    public void bindsWriteClipboardUpstreamActionWithRawStringPayload() {
        WriteClipboardActionHandler handler = new WriteClipboardActionHandler();
        assertEquals(UpstreamAction.WRITE_CLIPBOARD, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
