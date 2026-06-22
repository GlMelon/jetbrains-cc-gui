package com.github.claudecodegui.handler.tab;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 契约测试:CreateNewTabActionHandler 必须绑定 CREATE_NEW_TAB 枚举。
 * payloadType=String(create_new_tab 无请求体,payload 被忽略,仅为满足 dispatcher 契约)。
 * FrontendActionDispatcher 据此 O(1) 路由,绕开旧 TabHandler 字符串分派(AGENTS.md §2)。
 */
public class CreateNewTabActionHandlerTest {

    @Test
    public void bindsCreateNewTabUpstreamActionWithStringPayload() {
        CreateNewTabActionHandler handler = new CreateNewTabActionHandler();
        assertEquals(UpstreamAction.CREATE_NEW_TAB, handler.action());
        assertEquals(String.class, handler.payloadType());
    }
}
