package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * ChannelCliSession(ai-bridge channel 通道:omp/dsh/minimax)effort 发送前钳制验证:
 * omp(pi 词表全集)按表钳制(当前全集 = 归一化语义);dsh/minimax 未注册能力 →
 * 原样透传不拦(「不动」决策,selectModel 透传由 ai-bridge 测试锁定);
 * 空/非法 → null(字段省略)。能力表 SSOT 在 Java 侧,Node 只做 wire 映射(总则三/四)。
 */
public class ChannelCliSessionEffortClampTest {

    @Test
    public void ompClampsInvalidEffortToNullAndNormalizesValue() {
        assertEquals("max", ChannelCliSession.clampReasoningEffort(ProviderType.OMP, " MAX ", null));
        assertEquals("none", ChannelCliSession.clampReasoningEffort(ProviderType.OMP, "none", "pi-model"));
        assertNull(ChannelCliSession.clampReasoningEffort(ProviderType.OMP, "turbo", "pi-model"));
        assertNull(ChannelCliSession.clampReasoningEffort(ProviderType.OMP, null, "pi-model"));
        assertNull(ChannelCliSession.clampReasoningEffort(ProviderType.OMP, " ", "pi-model"));
    }

    @Test
    public void unregisteredProvidersPassEffortThroughUntouched() {
        // dsh/minimax 无能力表条目:原样透传(甚至非法值也交由下游按既有契约处理)
        assertEquals("xhigh", ChannelCliSession.clampReasoningEffort(ProviderType.DSH, "xhigh", "dsh-model"));
        assertEquals("turbo", ChannelCliSession.clampReasoningEffort(
                com.github.claudecodegui.session.runtime.ProviderType.MINIMAX, "turbo", "mimo"));
        assertNull(ChannelCliSession.clampReasoningEffort(ProviderType.DSH, null, "dsh-model"));
    }
}
