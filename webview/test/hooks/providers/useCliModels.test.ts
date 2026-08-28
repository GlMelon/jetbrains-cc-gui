import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetCliModelsCacheForTests, useCliModels, useOmpRoles } from '../../../src/hooks/providers/useCliModels';
import { CODEX_MODELS, KIMI_MODELS, OMP_ROLE_MODELS } from '../../../src/components/ChatInputBox/types';
import { installRuntimeProviderDispatchers } from '../../../src/utils/runtimeProviderCapabilities';

const sendBridgeEventMock = vi.hoisted(() => vi.fn());

vi.mock('../../../src/utils/bridge', () => ({
  sendBridgeEvent: (...args: unknown[]) => sendBridgeEventMock(...args),
}));

function emitCliModels(payload: unknown) {
  act(() => {
    window.setCliModels?.(JSON.stringify(payload));
  });
}

describe('useCliModels', () => {
  beforeEach(() => {
    sendBridgeEventMock.mockClear();
    __resetCliModelsCacheForTests();
    installRuntimeProviderDispatchers();
  });

  afterEach(() => {
    delete window.setCliModels;
    __resetCliModelsCacheForTests();
    vi.useRealTimers();
  });

  it('fetches the codex catalog when the codex provider is active', () => {
    renderHook(() => useCliModels('codex'));
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'codex');
  });

  it('fetches the grok catalog when the grok provider is active', () => {
    renderHook(() => useCliModels('grok'));
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'grok');
  });

  it('does not fetch for claude', () => {
    renderHook(() => useCliModels('claude'));
    expect(sendBridgeEventMock).not.toHaveBeenCalled();
  });

  it('falls back to the static CODEX_MODELS list before the catalog arrives', () => {
    const { result } = renderHook(() => useCliModels('codex'));
    expect(result.current.cliModels).toEqual(CODEX_MODELS);
    expect(result.current.cliModelsLoading).toBe(true);
  });

  it('stores the codex catalog and defaultModel from the backend payload', () => {
    const { result } = renderHook(() => useCliModels('codex'));
    emitCliModels({
      success: true,
      provider: 'codex',
      defaultModel: 'kimi-k3',
      models: [{ id: 'kimi-k3', label: 'kimi-k3', description: 'kimi-k3' }],
    });
    expect(result.current.cliModels).toEqual([
      // normalizeModels 会为每条补 identifier=id(动态 CLI 模型的 opaque key)。
      { id: 'kimi-k3', identifier: 'kimi-k3', label: 'kimi-k3', description: 'kimi-k3' },
    ]);
    expect(result.current.cliDefaultModel).toBe('kimi-k3');
    expect(result.current.cliModelsLoading).toBe(false);
    expect(result.current.cliModelsError).toBeNull();
  });

  it('保留同名 CLI 模型并按序号区分 identifier(防同名双选中,所有 CLI 通用)', () => {
    // CLI listModels 可能返回同名模型(如不同 source 的 glm-4)。旧逻辑 seen.has(id)
    // 直接丢弃第二个,用户看不到;且若都保留会因 identifier=id 相同导致 ModelSelect
    // 勾选串台(选智谱误亮 sensenova)。加固:不丢弃,identifier 加序号后缀区分。
    const { result } = renderHook(() => useCliModels('grok'));
    emitCliModels({
      success: true,
      provider: 'grok',
      models: [
        { id: 'glm-4', label: 'GLM 4', description: 'Zhipu source' },
        { id: 'glm-4', label: 'GLM 4', description: 'SenseNova source' },
      ],
    });
    const models = result.current.cliModels;
    // 不丢弃:两条都保留(旧逻辑 seen.has(id) 会丢第二个)
    expect(models).toHaveLength(2);
    // 首个保持 identifier=id,兼容已持久化的 selectedIdentifier(localStorage)
    expect(models[0].identifier).toBe('glm-4');
    // 第二个 identifier 加序号后缀区分,ModelSelect 据此精精确勾选不串台
    expect(models[1].identifier).toBe('glm-4#2');
    // 同名同 label 加序号让下拉两个同名项可辨
    expect(models[0].label).toBe('GLM 4');
    expect(models[1].label).toBe('GLM 4 #2');
    // id 保留原值:CLI 调用层仍按裸 id,identifier 只管 UI 定位
    expect(models.map((m) => m.id)).toEqual(['glm-4', 'glm-4']);
    // description 保留 CLI 提供的来源信息,辅助用户区分
    expect(models.map((m) => m.description)).toEqual(['Zhipu source', 'SenseNova source']);
  });

  it('同名不同 label 时不给 label 加序号(identifier 仍区分)', () => {
    const { result } = renderHook(() => useCliModels('grok'));
    emitCliModels({
      success: true,
      provider: 'grok',
      models: [
        { id: 'glm-4', label: 'GLM 4 Zhipu', description: 'zhipu' },
        { id: 'glm-4', label: 'GLM 4 SenseNova', description: 'sensenova' },
      ],
    });
    const models = result.current.cliModels;
    expect(models).toHaveLength(2);
    expect(models[0].identifier).toBe('glm-4');
    expect(models[1].identifier).toBe('glm-4#2');
    // label 本就不同,不加序号(避免画蛇添足)
    expect(models[0].label).toBe('GLM 4 Zhipu');
    expect(models[1].label).toBe('GLM 4 SenseNova');
  });

  it('falls back to CODEX_MODELS when the codex payload has no models (official provider)', () => {
    const { result } = renderHook(() => useCliModels('codex'));
    emitCliModels({
      success: true,
      provider: 'codex',
      defaultModel: 'gpt-5.6-sol',
      models: [],
    });
    expect(result.current.cliModels).toEqual(CODEX_MODELS);
    expect(result.current.cliDefaultModel).toBe('gpt-5.6-sol');
  });

  it('keeps kimi fallback behavior intact', () => {
    const { result } = renderHook(() => useCliModels('kimi'));
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'kimi');
    expect(result.current.cliModels).toEqual(KIMI_MODELS);
  });

  it('records backend errors and supports manual retry for codex', () => {
    const { result } = renderHook(() => useCliModels('codex'));
    emitCliModels({ success: false, provider: 'codex', error: 'node missing', models: [] });
    expect(result.current.cliModelsError).toBe('node missing');
    expect(result.current.cliModels).toEqual(CODEX_MODELS);

    sendBridgeEventMock.mockClear();
    act(() => {
      result.current.refreshCliModels('codex');
    });
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'codex');
  });

  it('refetches the codex catalog when the active codex provider changes', () => {
    const { result, rerender } = renderHook(
      ({ provider }) => useCliModels(provider),
      { initialProps: { provider: 'codex' } },
    );
    emitCliModels({
      success: true,
      provider: 'codex',
      defaultModel: 'kimi-k3',
      models: [{ id: 'kimi-k3', label: 'kimi-k3' }],
    });
    expect(result.current.modelsByProvider.codex?.length).toBe(1);

    sendBridgeEventMock.mockClear();
    act(() => {
      window.updateActiveCodexProvider?.(JSON.stringify({ id: 'other-provider' }));
    });
    // Cache cleared; effect refetches because the current provider is codex.
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'codex');
    rerender({ provider: 'codex' });
    expect(result.current.modelsByProvider.codex).toBeUndefined();
  });

  it('clears the codex cache without refetching when another provider is active', () => {
    const { result } = renderHook(() => useCliModels('claude'));
    emitCliModels({
      success: true,
      provider: 'codex',
      defaultModel: 'kimi-k3',
      models: [{ id: 'kimi-k3', label: 'kimi-k3' }],
    });
    expect(result.current.modelsByProvider.codex?.length).toBe(1);

    sendBridgeEventMock.mockClear();
    act(() => {
      window.updateActiveCodexProvider?.(JSON.stringify({ id: 'other-provider' }));
    });
    expect(sendBridgeEventMock).not.toHaveBeenCalled();
    expect(result.current.modelsByProvider.codex).toBeUndefined();
  });

  it('does not auto-refetch after an empty catalog response (spawn storm guard)', () => {
    const { result } = renderHook(() => useCliModels('opencode'));
    expect(sendBridgeEventMock).toHaveBeenCalledTimes(1);
    emitCliModels({ success: true, provider: 'opencode', models: [] });
    // 空 models + 空 fallback(OPENCODE_MODELS=[]):回填已发生,自动 effect 不得再发。
    // 修复前此处按 length 判定会 ~120ms 循环重发(实测日志 1600+ 次 spawn)。
    expect(sendBridgeEventMock).toHaveBeenCalledTimes(1);
    expect(result.current.cliModels).toEqual([]);
    expect(result.current.cliCatalogHasEntries).toBe(false);
    // 手动重试不受影响。
    act(() => {
      result.current.refreshCliModels('opencode');
    });
    expect(sendBridgeEventMock).toHaveBeenCalledTimes(2);
  });

  it('times out into an error state and falls back to static models', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCliModels('codex'));
    act(() => {
      vi.advanceTimersByTime(16_000);
    });
    expect(result.current.cliModelsLoading).toBe(false);
    expect(result.current.cliModelsError).toBe('timeout');
    expect(result.current.cliModels).toEqual(CODEX_MODELS);
  });

  it('reuses the module cache on remount so history→chat does not re-fetch', () => {
    const first = renderHook(() => useCliModels('opencode'));
    emitCliModels({
      success: true,
      provider: 'opencode',
      defaultModel: 'openai/gpt-5',
      models: [
        { id: 'opencode-default', label: 'OpenCode Default' },
        { id: 'openai/gpt-5', label: 'gpt-5' },
      ],
    });
    expect(first.result.current.cliModels.map((m) => m.id)).toEqual([
      'opencode-default',
      'openai/gpt-5',
    ]);
    first.unmount();

    sendBridgeEventMock.mockClear();
    const second = renderHook(() => useCliModels('opencode'));
    // Cache already has entries — no bridge round-trip on remount.
    expect(sendBridgeEventMock).not.toHaveBeenCalled();
    expect(second.result.current.cliModels.map((m) => m.id)).toEqual([
      'opencode-default',
      'openai/gpt-5',
    ]);
    expect(second.result.current.cliCatalogHasEntries).toBe(true);
    expect(second.result.current.cliDefaultModel).toBe('openai/gpt-5');
    expect(second.result.current.cliModelsLoading).toBe(false);
  });
});

describe('useOmpRoles', () => {
  beforeEach(() => {
    sendBridgeEventMock.mockClear();
    __resetCliModelsCacheForTests();
    installRuntimeProviderDispatchers();
  });

  afterEach(() => {
    delete window.setCliModels;
    __resetCliModelsCacheForTests();
    vi.useRealTimers();
  });

  it('falls back to the static smol/slow/plan roles before any payload arrives', () => {
    const { result } = renderHook(() => {
      useCliModels('omp');
      return useOmpRoles();
    });
    expect(result.current).toEqual(OMP_ROLE_MODELS);
  });

  it('populates omp roles from the setCliModels payload', () => {
    const { result } = renderHook(() => {
      useCliModels('omp');
      return useOmpRoles();
    });
    emitCliModels({
      success: true,
      provider: 'omp',
      models: [{ id: 'openai/gpt-5', label: 'gpt-5' }],
      roles: [
        { id: 'smol', label: 'Smol', description: 'openai/gpt-5-mini' },
        { id: 'designer', label: 'Designer', description: 'opencode-go/deepseek-v4-flash' },
      ],
    });
    expect(result.current).toEqual([
      { id: 'smol', identifier: 'smol', label: 'Smol', description: 'openai/gpt-5-mini' },
      { id: 'designer', identifier: 'designer', label: 'Designer', description: 'opencode-go/deepseek-v4-flash' },
    ]);
  });

  it('keeps the static fallback when the payload carries no usable roles', () => {
    const { result } = renderHook(() => {
      useCliModels('omp');
      return useOmpRoles();
    });
    emitCliModels({
      success: true,
      provider: 'omp',
      models: [{ id: 'openai/gpt-5', label: 'gpt-5' }],
      roles: 'not-an-array',
    });
    expect(result.current).toEqual(OMP_ROLE_MODELS);
  });

  it('ignores roles payloads for other providers', () => {
    const { result } = renderHook(() => {
      useCliModels('kimi');
      return useOmpRoles();
    });
    emitCliModels({
      success: true,
      provider: 'kimi',
      models: [{ id: 'kimi-k3', label: 'kimi-k3' }],
      roles: [{ id: 'designer', label: 'Designer' }],
    });
    expect(result.current).toEqual(OMP_ROLE_MODELS);
  });
});
