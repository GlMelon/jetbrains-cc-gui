import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CacheKeys, McpServer, ServerToolsState } from '../types';
import { readToolsCache, writeToolsCache } from '../utils';
import { useServerManagement } from './useServerManagement';

const sendToJavaMock = vi.hoisted(() => vi.fn());

const cacheKeys: CacheKeys = {
  SERVERS: 'test.mcp.servers',
  STATUS: 'test.mcp.status',
  TOOLS: 'test.mcp.tools',
  LAST_SERVER_ID: 'test.mcp.last-server',
};

const server: McpServer = {
  id: 'server-a',
  name: 'Primary Server',
  server: { command: 'node' },
};

function lastSentAction(): { type: string; content: Record<string, unknown> } {
  const raw = sendToJavaMock.mock.calls.at(-1)?.[0] as string;
  const envelope = JSON.parse(raw);
  return { type: envelope.type, content: JSON.parse(envelope.content) };
}

beforeEach(() => {
  localStorage.clear();
  sendToJavaMock.mockClear();
  window.sendToJava = sendToJavaMock;
});

afterEach(() => {
  vi.clearAllMocks();
  delete window.sendToJava;
});

describe('useServerManagement tool cache invalidation', () => {
  it('clears persisted tools whenever a server is toggled', () => {
    const setServerTools = vi.fn() as unknown as React.Dispatch<React.SetStateAction<ServerToolsState>>;
    const hook = renderHook(() => useServerManagement({
      cacheKeys,
      setServerTools,
      loadServers: vi.fn(),
      loadServerStatus: vi.fn(),
      loadServerTools: vi.fn(),
      onLog: vi.fn(),
      onToast: vi.fn(),
      t: (key) => key,
    }));

    writeToolsCache(server.id, [{ name: 'stale-tool' }], cacheKeys);

    act(() => {
      hook.result.current.handleToggleServer(server, false);
    });

    expect(readToolsCache(server.id, cacheKeys)).toBeNull();
    expect(setServerTools).toHaveBeenCalledTimes(1);
    const sent = lastSentAction();
    expect(sent.type).toBe('toggle_mcp_server');
    expect(sent.content).toMatchObject({
      id: server.id,
      enabled: false,
    });
  });

  it('reports a toggle success immediately on the unified global list', () => {
    const onToast = vi.fn();
    const loadServers = vi.fn();
    const loadServerStatus = vi.fn();
    const hook = renderHook(() => useServerManagement({
      cacheKeys,
      setServerTools: vi.fn() as unknown as React.Dispatch<React.SetStateAction<ServerToolsState>>,
      loadServers,
      loadServerStatus,
      loadServerTools: vi.fn(),
      onLog: vi.fn(),
      onToast,
      t: (key) => key,
    }));

    act(() => {
      hook.result.current.handleToggleServer(server, false);
    });

    expect(lastSentAction().type).toBe('toggle_mcp_server');
    expect(onToast).toHaveBeenCalledTimes(1);
    expect(loadServers).toHaveBeenCalledTimes(1);
    expect(loadServerStatus).toHaveBeenCalledTimes(1);
  });
});
