package com.github.claudecodegui.service;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Bug 3 修复:commitAi 路径必须像 chat 主路径(SessionSendService)一样通过 registry 解析 actualModel。
 *
 * <p>GitCommitMessageService 构造依赖 CodemossSettingsService.getInstance() → ApplicationManager
 * 单例,纯 JUnit 无 IntelliJ Application 无法实例化(与 SessionSendService 同属"重依赖未独立单测"类)。
 * 故把 actualModel 解析提取为接收 registry 的纯静态方法 {@link GitCommitMessageService#resolveActualModel},
 * 脱离 platform 单测 actualModel 解析逻辑(含 getDefault fallback)。
 *
 * <p>callClaudeAPI 的胶水连接(解析 → sendClaudeCommitMessage → bridge 15 参重载 actualModel 位置)
 * 由编译验证 + 镜像 SessionSendService:341/367 已验证模式保证。
 */
public class GitCommitMessageServiceModelResolutionTest {

    @Test
    public void resolvesActualModelFromCustomRegistry() {
        // 用户自定义 registry:sonnet role → glm-5.2(修复前被 commitAi 绕过)
        ModelRegistryConfig registry = new ModelRegistryConfig(List.of(
                new ModelConfig(
                        "claude-role-sonnet", "claude", "sonnet", "Sonnet",
                        "glm-5.2", "custom override",
                        200000, false, true)));

        String actualModel = GitCommitMessageService.resolveActualModel(
                registry, "claude", "claude-role-sonnet");

        assertEquals("glm-5.2", actualModel);
    }

    @Test
    public void returnsNullWhenRegistryHasNoActualModelOverride() {
        // 默认 registry:actualModel 空 → null
        // (与修复前 actualModel 链路硬编码 null 一致,零回归)
        ModelRegistryConfig registry = ModelRegistryConfig.getDefault();

        String actualModel = GitCommitMessageService.resolveActualModel(
                registry, "claude", "claude-role-sonnet");

        assertNull(actualModel);
    }

    @Test
    public void fallsBackToDefaultRegistryWhenResolutionThrows() {
        // null registry 触发 NPE → getDefault fallback(默认 actualModel 空 → null)
        // 镜像 SessionSendService.resolveModelSelection 的 try/catch + getDefault 容错策略
        String actualModel = GitCommitMessageService.resolveActualModel(
                null, "claude", "claude-role-sonnet");

        assertNull(actualModel);
    }
}
