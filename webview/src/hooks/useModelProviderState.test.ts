import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendAction } from '../bridge/typed';
import { __setModelRegistryForTests, resetModelRegistryForTests } from '../utils/modelRegistry';
import { bridgeHub } from '../bridge';
import { DOWNSTREAM } from '../generated/protocol';

vi.mock('../bridge/typed', async () => {
  const actual = await vi.importActual<typeof import('../bridge/typed')>('../bridge/typed');
  return { ...actual, sendAction: vi.fn() };
});

const t = ((key: string) => key) as never;
const addToast = vi.fn();

describe('useModelProviderState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    bridgeHub.reset();
    bridgeHub.markReady();
    resetModelRegistryForTests();
  });

  it('uses one SDK status state for callbacks and computed install status', () => {
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    expect(result.current.sdkStatusLoaded).toBe(false);
    expect(result.current.currentSdkInstalled).toBe(false);

    act(() => {
      result.current.setSdkStatus({
        'claude-sdk': { status: 'installed' },
      });
      result.current.setSdkStatusLoaded(true);
    });

    expect(result.current.sdkStatusLoaded).toBe(true);
    expect(result.current.currentSdkInstalled).toBe(true);
  });

  it('uses registry model capability when selecting a third-party Claude 1M model', () => {
    __setModelRegistryForTests({
      items: [{
        id: 'mimo-v2.5-pro',
        provider: 'claude',
        role: 'sonnet',
        label: 'MiMo',
        actualModel: 'mimo-v2.5-pro',
        contextWindow: 1_000_000,
        supports1MContext: true,
        readOnly: false,
        enabled: true,
      }],
    });
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    act(() => {
      result.current.handleModelSelect('mimo-v2.5-pro', 1_000_000);
    });

    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    // P1-A2:前端只发送意图(longContextEnabled),effectiveContextWindow 由后端权威计算。
    // 1M 模型 + longContext 默认开启 → 发送 longContextEnabled: true。
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'mimo-v2.5-pro',
      longContextEnabled: true,
    });
  });

  it('refreshes the current session model when registry capabilities change for the selected model', () => {
    __setModelRegistryForTests({
      items: [{
        id: 'mimo-v2.5-pro',
        provider: 'claude',
        role: 'sonnet',
        label: 'MiMo',
        actualModel: 'mimo-v2.5-pro',
        contextWindow: 200_000,
        supports1MContext: false,
        readOnly: false,
        enabled: true,
      }],
    });
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    act(() => {
      result.current.handleModelSelect('mimo-v2.5-pro', 200_000);
    });
    vi.clearAllMocks();

    act(() => {
      __setModelRegistryForTests({
        items: [{
          id: 'mimo-v2.5-pro',
          provider: 'claude',
          label: 'MiMo',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        }],
      });
    });

    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    // P1-A2:registry 变更触发 effect 重发意图。先前选 200k 模型时 longContext 已被
    // auto-reset 为 false,故刷新后仍发送 longContextEnabled: false(effectiveContextWindow
    // 由后端按 registry 1M 权威计算,前端不再发送 contextWindow)。
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'mimo-v2.5-pro',
      longContextEnabled: false,
    });
  });

  it('applies backend model selection event as display state', () => {
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'claude',
        selectedModel: 'mimo-v2.5-pro',
        effectiveContextWindow: 1_000_000,
        supportsLongContext: true,
      }));
    });

    expect(result.current.currentProvider).toBe('claude');
    expect(result.current.selectedClaudeModel).toBe('mimo-v2.5-pro');
    expect(result.current.selectedModel).toBe('mimo-v2.5-pro');
    expect(result.current.longContextEnabled).toBe(true);
  });
});
