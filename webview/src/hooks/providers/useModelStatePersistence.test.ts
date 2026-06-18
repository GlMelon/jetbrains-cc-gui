// @vitest-environment happy-dom

import {renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {useModelStatePersistence, type UseModelStatePersistenceOptions} from './useModelStatePersistence';
import {CLAUDE_ROLE_MODEL_IDS} from '../../components/ChatInputBox/types';
import {__setModelRegistryForTests, resetModelRegistryForTests} from '../../utils/modelRegistry';

describe('useModelStatePersistence', () => {
    const createOptions = (
        overrides: Partial<UseModelStatePersistenceOptions> = {},
    ): UseModelStatePersistenceOptions => ({
        setCurrentProvider: vi.fn(),
        setSelectedClaudeModel: vi.fn(),
        setSelectedCodexModel: vi.fn(),
        setClaudePermissionMode: vi.fn(),
        setCodexPermissionMode: vi.fn(),
        setPermissionMode: vi.fn(),
        setLongContextEnabled: vi.fn(),
        setReasoningEffort: vi.fn(),
        setCodexFastMode: vi.fn(),
        currentProvider: 'claude',
        selectedClaudeModel: CLAUDE_ROLE_MODEL_IDS.sonnet,
        selectedCodexModel: 'provider-catalog-model',
        claudePermissionMode: 'acceptEdits',
        codexPermissionMode: 'default',
        longContextEnabled: true,
        reasoningEffort: 'high',
        ...overrides,
    });

    beforeEach(() => {
        localStorage.clear();
        window.sendToJava = vi.fn();
        resetModelRegistryForTests();
    });

    it('hydrates local UI preferences without mutating backend session state', () => {
        localStorage.setItem('model-selection-state', JSON.stringify({
            provider: 'codex',
            codexModel: 'provider-catalog-model',
            claudeModel: CLAUDE_ROLE_MODEL_IDS.sonnet,
            claudePermissionMode: 'acceptEdits',
            codexPermissionMode: 'plan',
            longContextEnabled: true,
            reasoningEffort: 'high',
        }));

        renderHook(() => useModelStatePersistence(createOptions()));

        const calls = (window.sendToJava as any).mock.calls.map(([payload]: [string]) => payload);
        expect(calls).not.toContainEqual(expect.stringContaining('set_provider:'));
        expect(calls).not.toContainEqual(expect.stringContaining('set_mode:'));
        expect(calls).not.toContainEqual(expect.stringContaining('set_model:'));
    });

    it('preserves codex plan mode during hydration', () => {
        const options = createOptions();
        localStorage.setItem('model-selection-state', JSON.stringify({
            provider: 'codex',
            codexModel: 'provider-catalog-model',
            claudeModel: CLAUDE_ROLE_MODEL_IDS.sonnet,
            claudePermissionMode: 'acceptEdits',
            codexPermissionMode: 'plan',
            longContextEnabled: true,
            reasoningEffort: 'high',
        }));

        renderHook(() => useModelStatePersistence(options));

        expect(options.setCodexPermissionMode).toHaveBeenCalledWith('plan');
        expect(options.setPermissionMode).toHaveBeenCalledWith('plan');
    });

    it('restores a persisted custom Claude model after the registry loads', () => {
        // localStorage 持久化了自定义模型 mimo-v2.5。mount 时 registry 仍为 DEFAULT
        // (仅 4 个内置 role),resolve 会暂回退到 sonnet;registry 真正收录该模型后,
        // 重校验 effect 应恢复原始 id。回归问题1:刷新后自定义模型不再丢失。
        localStorage.setItem('model-selection-state', JSON.stringify({
            provider: 'claude',
            claudeModel: 'mimo-v2.5',
        }));

        const options = createOptions();
        renderHook(() => useModelStatePersistence(options));

        // mount 阶段:DEFAULT registry 不含 mimo-v2.5 → 回退 sonnet
        expect(options.setSelectedClaudeModel).toHaveBeenCalledWith(CLAUDE_ROLE_MODEL_IDS.sonnet);
        expect(options.setSelectedClaudeModel).not.toHaveBeenCalledWith('mimo-v2.5');

        // 模拟后端推送的真实 registry(含自定义模型)
        __setModelRegistryForTests({
            items: [
                {
                    id: 'claude-role-sonnet',
                    provider: 'claude',
                    role: 'sonnet',
                    label: 'Sonnet',
                    actualModel: 'glm5.2',
                    contextWindow: 1_000_000,
                    enabled: true,
                },
                {
                    id: 'mimo-v2.5',
                    provider: 'claude',
                    role: 'sonnet',
                    label: 'mimo-v2.5',
                    actualModel: 'mimo-v2.5',
                    contextWindow: 1_000_000,
                    enabled: true,
                },
            ],
        });

        // 重校验 effect 应恢复持久化的自定义模型 id
        expect(options.setSelectedClaudeModel).toHaveBeenCalledWith('mimo-v2.5');
    });
});
