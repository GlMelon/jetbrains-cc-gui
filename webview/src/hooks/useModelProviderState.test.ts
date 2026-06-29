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

  it('applies backend model selection event with opencode provider as independent state', () => {
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'opencode',
        selectedModel: 'mimo-v2.5',
        effectiveContextWindow: 1_000_000,
        supportsLongContext: false,
      }));
    });

    // 修复C:opencode 不再被订阅归为 claude,拥有独立 selectedOpenCodeModel。
    expect(result.current.currentProvider).toBe('opencode');
    expect(result.current.selectedOpenCodeModel).toBe('mimo-v2.5');
    expect(result.current.selectedModel).toBe('mimo-v2.5');
  });

  it('sends opencode model selection to independent state when selecting in opencode provider', () => {
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));
    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'opencode',
        selectedModel: 'mimo-v2.5',
      }));
    });
    vi.clearAllMocks();

    act(() => {
      result.current.handleModelSelect('mimo-v2.5-pro', 1_000_000);
    });

    expect(result.current.selectedOpenCodeModel).toBe('mimo-v2.5-pro');
    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'mimo-v2.5-pro',
      contextWindow: 1_000_000,
    });
  });

  it('switches to opencode provider sending its independent selected model', () => {
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));
    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'opencode',
        selectedModel: 'mimo-v2.5',
      }));
    });
    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'claude',
        selectedModel: 'claude-role-sonnet',
        supportsLongContext: true,
      }));
    });
    vi.clearAllMocks();

    act(() => {
      result.current.handleProviderSelect('opencode', undefined);
    });

    expect(result.current.currentProvider).toBe('opencode');
    const providerCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_provider',
    );
    expect(providerCall).toBeTruthy();
    expect(providerCall![1]).toBe('opencode');
    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    // 修复C:切到 opencode 发送独立 selectedOpenCodeModel,而非 selectedClaudeModel。
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'mimo-v2.5',
    });
  });

  it('re-sends opencode model when registry updates for the selected opencode model', () => {
    __setModelRegistryForTests({
      items: [{
        id: 'mimo-v2.5', provider: 'opencode', label: 'MiMo',
        contextWindow: 1_000_000, supports1MContext: false, readOnly: true, enabled: true,
      }],
    });
    renderHook(() => useModelProviderState({ addToast, t }));
    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'opencode',
        selectedModel: 'mimo-v2.5',
      }));
    });
    vi.clearAllMocks();

    act(() => {
      __setModelRegistryForTests({
        items: [{
          id: 'mimo-v2.5', provider: 'opencode', label: 'MiMo',
          contextWindow: 2_000_000, supports1MContext: false, readOnly: true, enabled: true,
        }],
      });
    });

    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    // 修复C:registry 变更时 opencode 重发独立 selectedOpenCodeModel(原 bug 发 selectedCodexModel 空串)。
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'mimo-v2.5',
    });
  });

  it('defaults to first model of switched provider when selected model is cross-provider polluted', () => {
    // registry 播种:claude role + opencode glm-5.2(对称 ReadOnlyDefaultModels 下发产物)。
    __setModelRegistryForTests({
      items: [
        { id: 'claude-role-sonnet', provider: 'claude', label: 'Sonnet', contextWindow: 200_000, supports1MContext: true, readOnly: true, enabled: true },
        { id: 'glm-5.2', provider: 'opencode', label: 'GLM-5.2', contextWindow: 128_000, supports1MContext: false, readOnly: true, enabled: true },
      ],
    });
    const { result } = renderHook(() => useModelProviderState({ addToast, t }));

    // 复现持久化污染:selectedOpenCodeModel 被历史 MODEL_SELECTION 设成跨 provider 的 claude-role-sonnet
    // (生产中 useModelStatePersistence 持久化 opencodeModel 键,重启后 hydrate 回来形成自强化污染循环)。
    act(() => {
      bridgeHub.dispatch(DOWNSTREAM.MODEL_SELECTION, JSON.stringify({
        provider: 'opencode',
        selectedModel: 'claude-role-sonnet',
      }));
    });
    expect(result.current.selectedOpenCodeModel).toBe('claude-role-sonnet');
    vi.clearAllMocks();

    act(() => {
      result.current.handleProviderSelect('opencode', undefined);
    });

    expect(result.current.currentProvider).toBe('opencode');
    const setModelCall = vi.mocked(sendAction).mock.calls.find(
      ([type]) => type === 'set_session_model',
    );
    expect(setModelCall).toBeTruthy();
    // 切 opencode 后默认第一个 opencode 模型(glm-5.2),而非透传污染的 claude-role-sonnet
    // (后者使 opencode CLI 收到 -m claude-role-sonnet → "进程退出但未返回任何内容" 静默失败)。
    expect(JSON.parse(setModelCall![1] as string)).toMatchObject({
      model: 'glm-5.2',
    });
    // selectedOpenCodeModel 同步校正,打破 localStorage 持久化污染循环。
    expect(result.current.selectedOpenCodeModel).toBe('glm-5.2');
  });
});
