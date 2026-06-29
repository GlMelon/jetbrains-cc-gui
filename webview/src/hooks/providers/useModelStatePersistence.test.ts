// @vitest-environment happy-dom

import {renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {useModelStatePersistence, type UseModelStatePersistenceOptions} from './useModelStatePersistence';
import {CLAUDE_ROLE_MODEL_IDS} from '../../components/ChatInputBox/types';
import {resetModelRegistryForTests} from '../../utils/modelRegistry';

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
        setSelectedOpenCodeModel: vi.fn(),
        currentProvider: 'claude',
        selectedClaudeModel: CLAUDE_ROLE_MODEL_IDS.sonnet,
        selectedCodexModel: 'provider-catalog-model',
        selectedOpenCodeModel: 'mimo-v2.5',
        claudePermissionMode: 'acceptEdits',
        codexPermissionMode: 'default',
        longContextEnabled: true,
        reasoningEffort: 'high',
        codexFastMode: 'normal',
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

    it('preserves a persisted custom Claude model directly on mount', () => {
        // A3:resolveClaudeModelId 不再回退——持久化的自定义模型 id 在 mount 时原样保留,
        // 无需等待 registry 加载恢复(原"回退 sonnet 再恢复"的两阶段机制已消除)。
        localStorage.setItem('model-selection-state', JSON.stringify({
            provider: 'claude',
            claudeModel: 'mimo-v2.5',
        }));

        const options = createOptions();
        renderHook(() => useModelStatePersistence(options));

        // mount 阶段:直接保留持久化的自定义模型 id,不回退 role。
        expect(options.setSelectedClaudeModel).toHaveBeenCalledWith('mimo-v2.5');
        expect(options.setSelectedClaudeModel).not.toHaveBeenCalledWith(CLAUDE_ROLE_MODEL_IDS.sonnet);
    });

    it('hydrates opencode model into independent state', () => {
        localStorage.setItem('model-selection-state', JSON.stringify({
            provider: 'opencode',
            opencodeModel: 'mimo-v2.5-pro',
        }));

        const options = createOptions();
        renderHook(() => useModelStatePersistence(options));

        expect(options.setCurrentProvider).toHaveBeenCalledWith('opencode');
        // 修复C:opencode 拥有独立持久化模型(原仅 codex 持久化,opencode 不存)。
        expect(options.setSelectedOpenCodeModel).toHaveBeenCalledWith('mimo-v2.5-pro');
    });
});
