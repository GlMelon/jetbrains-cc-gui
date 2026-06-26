package com.github.claudecodegui.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * C6 守门:ProtocolValue 的 desc() 约定。
 * 验证 default desc() 不破坏现有 value-only 枚举,且为未来带描述的业务枚举(C2)提供接口级来源。
 */
public class ProtocolValueTest {

    @Test
    public void descDefaultReturnsEmptyStringForValueOnlyEnums() {
        // 现有 value-only 枚举不覆盖 desc(),走 default,应返回空串(零改动合规)
        assertEquals("", UpstreamAction.SEND_MESSAGE.desc());
        assertEquals("", DownstreamEvent.STREAM_START.desc());
    }

    @Test
    public void descNeverReturnsNull() {
        // desc 约定:始终非 null,便于直接序列化/下发前端消费
        assertNotNull(UpstreamAction.SEND_MESSAGE.desc());
        assertNotNull(DownstreamEvent.STREAM_START.desc());
    }

    @Test
    public void valueContractUnchangedAfterDescIntroduction() {
        // 守门:value() 仍是主标识,desc() 引入不影响 value 契约
        assertEquals("send_message", UpstreamAction.SEND_MESSAGE.value());
        assertEquals("stream.start", DownstreamEvent.STREAM_START.value());
    }

    @Test
    public void customDescCanBeProvidedByOverriding() {
        // 验证 desc() 可被实现类覆盖(为 C2 业务枚举铺路):匿名类显式覆盖 desc()
        ProtocolValue withDesc = new ProtocolValue() {
            @Override
            public String value() { return "some.value"; }
            @Override
            public String desc() { return "custom-description"; }
        };
        assertEquals("some.value", withDesc.value());
        assertEquals("custom-description", withDesc.desc());

        // value-only 实现仅实现 value(),desc() 走 default 返回空串
        ProtocolValue valueOnly = new ProtocolValue() {
            @Override
            public String value() { return "another.value"; }
        };
        assertEquals("another.value", valueOnly.value());
        assertEquals("", valueOnly.desc());
    }
}
