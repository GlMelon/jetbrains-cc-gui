package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SetNodePathActionHandlerTest {

    /**
     * 契约:SetNodePathActionHandler 必须绑定 SET_NODE_PATH 枚举并以 String 为原始载荷类型。
     */
    @Test
    public void bindsSetNodePathUpstreamActionWithRawStringPayload() {
        SetNodePathActionHandler handler = new SetNodePathActionHandler();
        assertEquals(UpstreamAction.SET_NODE_PATH, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
