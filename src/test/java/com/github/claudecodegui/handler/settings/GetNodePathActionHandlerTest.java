package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GetNodePathActionHandlerTest {

    /**
     * 契约:GetNodePathActionHandler 必须绑定 GET_NODE_PATH 枚举并以 String 为原始载荷类型。
     * FrontendActionDispatcher 据此 O(1) 路由,绕开 SettingsHandler 字符串 switch(AGENTS.md §2)。
     */
    @Test
    public void bindsGetNodePathUpstreamActionWithRawStringPayload() {
        GetNodePathActionHandler handler = new GetNodePathActionHandler();
        assertEquals(UpstreamAction.GET_NODE_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
