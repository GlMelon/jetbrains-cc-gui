package com.github.claudecodegui.handler.rewind;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约测试:RewindFilesActionHandler 必须绑定 REWIND_FILES 枚举并以 String 为载荷类型
 * (handler 内部 gson.fromJson 解析请求 JSON,dispatcher 不预解析)。
 */
public class RewindFilesActionHandlerTest {

    @Test
    public void bindsRewindFilesUpstreamActionWithStringPayload() {
        RewindFilesActionHandler handler = new RewindFilesActionHandler();
        assertEquals(UpstreamAction.REWIND_FILES, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
