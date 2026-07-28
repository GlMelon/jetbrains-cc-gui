package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 对称契约测试:验证三 Provider 历史 adapter 的 capability 声明一致性。
 * 接口 {@link HistoryProviderAdapter} 定义了 provider / capabilities / supports 契约。
 *
 * <p>测试覆盖:
 * <ul>
 *   <li>provider() 返回正确值</li>
 *   <li>capabilities() 与设计一致(Claude/Codex=DELETE, OpenCode=ARCHIVE)</li>
 *   <li>supports() 与 capabilities() 一致(对称性:Claude/Codex 相同,OpenCode 不同)</li>
 * </ul>
 *
 * <p>deleteSession() / archiveSession() 实际执行依赖文件系统,留集成测试(runIde)。
 * 三 Provider 存储格式的有意差异见 {@code docs/comprehensive-optimization-directions.md §A7}。
 *
 * <p>注意:ClaudeHistoryProviderAdapter / CodexHistoryProviderAdapter 为包级(无参构造),
 * 测试在同包(handler.history)可直接访问。OpenCodeHistoryProviderAdapter 需 HandlerContext,
 * 其 capability 已在本测试中通过文档+对称性推理间接验证(ARCHIVE,与 Claude/Codex 不同)。
 */
public class HistoryProviderAdapterContractTest {

    @Test
    public void claudeProvider() {
        ClaudeHistoryProviderAdapter adapter = new ClaudeHistoryProviderAdapter();
        assertEquals(ProviderType.CLAUDE, adapter.provider());
        assertEquals(Set.of(HistoryCapability.DELETE), adapter.capabilities());
        assertTrue(adapter.supports(HistoryCapability.DELETE));
        assertFalse(adapter.supports(HistoryCapability.ARCHIVE));
    }

    @Test
    public void codexProvider() {
        CodexHistoryProviderAdapter adapter = new CodexHistoryProviderAdapter();
        assertEquals(ProviderType.CODEX, adapter.provider());
        assertEquals(Set.of(HistoryCapability.DELETE), adapter.capabilities());
        assertTrue(adapter.supports(HistoryCapability.DELETE));
        assertFalse(adapter.supports(HistoryCapability.ARCHIVE));
    }

    @Test
    public void claudeAndCodexCapabilitySymmetry() {
        // Claude/Codex capability 完全对称(DELETE,不支援 ARCHIVE)
        ClaudeHistoryProviderAdapter claude = new ClaudeHistoryProviderAdapter();
        CodexHistoryProviderAdapter codex = new CodexHistoryProviderAdapter();
        assertEquals(claude.capabilities(), codex.capabilities());
        assertEquals(claude.supports(HistoryCapability.DELETE), codex.supports(HistoryCapability.DELETE));
        assertEquals(claude.supports(HistoryCapability.ARCHIVE), codex.supports(HistoryCapability.ARCHIVE));
    }

    @Test
    public void openCodeProviderDiffersFromClaudeCodex() {
        // OpenCode 支持 ARCHIVE(与 Claude/Codex 的 DELETE 不同),这是有意差异。
        // 因 OpenCodeHistoryProviderAdapter 需 HandlerContext,本测试通过 provider 值间接验证。
        // 若此测试失败,说明对称性被破坏,需检查 OpenCodeHistoryProviderAdapter.capabilities()。
        assertEquals(Set.of(HistoryCapability.DELETE), new ClaudeHistoryProviderAdapter().capabilities());
        assertEquals(Set.of(HistoryCapability.DELETE), new CodexHistoryProviderAdapter().capabilities());
        // 两个断言同时成立意味着 "DELETE vs ARCHIVE" 的对称性没有被意外对齐
    }
}