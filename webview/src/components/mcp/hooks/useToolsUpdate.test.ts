import { act, renderHook } from '@testing-library/react';
import type { Dispatch, SetStateAction } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CacheKeys, ServerToolsState } from '../types';
import { useToolsUpdate } from './useToolsUpdate';

const cacheKeys: CacheKeys = {
  SERVERS: 'test.servers',
  STATUS: 'test.status',
  TOOLS: 'test.tools',
  LAST_SERVER_ID: 'test.lastServerId',
};

function renderToolsHook(overrides: {
  setServerTools?: Dispatch<SetStateAction<ServerToolsState>>;
  acceptToolsResponse?: (serverId: string, requestId: string) => boolean;
  failPendingToolsRequests?: (error: string) => void;
  onLog?: (...args: unknown[]) => void;
} = {}) {
  return renderHook(() => useToolsUpdate({
    cacheKeys,
    setServerTools: overrides.setServerTools
      ?? (vi.fn() as unknown as Dispatch<SetStateAction<ServerToolsState>>),
    acceptToolsResponse: overrides.acceptToolsResponse ?? (() => true),
    failPendingToolsRequests: overrides.failPendingToolsRequests ?? vi.fn(),
    onLog: overrides.onLog ?? vi.fn(),
  }));
}

afterEach(() => {
  delete window.updateMcpServerTools;
});

describe('useToolsUpdate legacy alias', () => {
  it('registers the legacy window callback for the unified tools event', () => {
    const setServerTools = vi.fn() as unknown as Dispatch<SetStateAction<ServerToolsState>>;
    const hook = renderToolsHook({ setServerTools });

    expect(window.updateMcpServerTools).toBeTypeOf('function');

    act(() => {
      window.updateMcpServerTools?.(JSON.stringify({
        requestId: 'req-1',
        serverId: 'server-a',
        serverName: 'Server A',
        tools: [{ name: 'tool-a' }],
      }));
    });

    expect(setServerTools).toHaveBeenCalled();
    hook.unmount();
  });

  it('does not clear a callback replaced by a newer owner', () => {
    const firstHook = renderToolsHook();
    const replacement = vi.fn();
    window.updateMcpServerTools = replacement;

    firstHook.unmount();

    expect(window.updateMcpServerTools).toBe(replacement);
  });

  it('ignores stale responses rejected by acceptToolsResponse', () => {
    const setServerTools = vi.fn() as unknown as Dispatch<SetStateAction<ServerToolsState>>;
    const hook = renderToolsHook({ setServerTools, acceptToolsResponse: () => false });

    act(() => {
      window.updateMcpServerTools?.(JSON.stringify({
        requestId: 'stale-req',
        serverId: 'server-a',
        tools: [{ name: 'tool-a' }],
      }));
    });

    expect(setServerTools).not.toHaveBeenCalled();
    hook.unmount();
  });
});

describe('useToolsUpdate empty tool result', () => {
  it('logs a connected server with no tools as a warning', () => {
    const setServerTools = vi.fn() as unknown as Dispatch<SetStateAction<ServerToolsState>>;
    const onLog = vi.fn();
    const hook = renderToolsHook({ setServerTools, onLog });

    act(() => {
      window.updateMcpServerTools?.(JSON.stringify({
        requestId: 'req-1',
        serverId: 'empty-server',
        serverName: 'Empty server',
        tools: [],
        error: null,
      }));
    });

    expect(onLog).toHaveBeenCalledWith(
      expect.any(String),
      'warning',
      undefined,
      'Empty server',
    );
    hook.unmount();
  });
});
