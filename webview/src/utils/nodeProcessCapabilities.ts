/**
 * Node process capabilities subscriber registry.
 *
 * Mirrors runtimeProviderCapabilities.ts — installs single dispatchers on
 * `window.updateNodeProcesses` / `window.nodeProcessKillResult` and routes
 * incoming JSON payloads to subscribed listeners.
 *
 * Multiple consumers (e.g. the settings menu badge and the panel itself) can
 * subscribe without overwriting each other's callbacks.
 */

import { sendBridgeEvent } from './bridge';
import { bridgeHub, registerLegacyAlias } from '../bridge';
import { createCallbackChannel } from './createCallbackChannel';

export type NodeProcessKind = 'DAEMON' | 'CHANNEL' | 'ORPHAN';

export interface NodeProcessInfo {
  id: string;
  kind: NodeProcessKind;
  provider?: string;
  pid: number;
  alive: boolean;
  startedAt: number;
  uptimeMs: number;
  command?: string;
  heapUsed?: number;
  activeRequestCount: number;
  channelId?: string;
  sessionId?: string;
  tabName?: string;
  orphan: boolean;
}

export interface NodeProcessTotals {
  daemon: number;
  channel: number;
  orphan: number;
  all: number;
}

export interface NodeProcessSnapshot {
  snapshotAt: number;
  totals: NodeProcessTotals;
  processes: NodeProcessInfo[];
}

export interface NodeProcessKillResult {
  pid?: number;
  id?: string;
  success?: boolean;
  error?: string;
  killed?: number;
  restart?: boolean;
}

type SnapshotListener = (snapshot: NodeProcessSnapshot) => void;
type KillResultListener = (result: NodeProcessKillResult) => void;

function safeParseSnapshot(json: string): NodeProcessSnapshot | null {
  try {
    const parsed = JSON.parse(json) as NodeProcessSnapshot;
    if (!parsed || !Array.isArray(parsed.processes)) {
      return null;
    }
    return parsed;
  } catch (error) {
    console.error('[nodeProcessCapabilities] Failed to parse snapshot:', error);
    return null;
  }
}

function safeParseKillResult(json: string): NodeProcessKillResult | null {
  try {
    return JSON.parse(json) as NodeProcessKillResult;
  } catch (error) {
    console.error('[nodeProcessCapabilities] Failed to parse kill result:', error);
    return null;
  }
}

// 创建回调通道
const snapshotChannel = createCallbackChannel<NodeProcessSnapshot>({
  name: 'nodeProcess:snapshot',
});

const killResultChannel = createCallbackChannel<NodeProcessKillResult>({
  name: 'nodeProcess:killResult',
});

let dispatcherSubscribed = false;
export function installNodeProcessDispatchers(): void {
  // [归一化] 经 bridgeHub 订阅,替代旧 window.xxx 覆盖。
  // 别名每次重新注册(幂等,刷新 window.xxx 转发函数);订阅只发生一次避免累积。
  registerLegacyAlias('updateNodeProcesses', 'node.process_list');
  registerLegacyAlias('nodeProcessKillResult', 'node.process_kill_result');
  if (dispatcherSubscribed) return;
  dispatcherSubscribed = true;
  bridgeHub.subscribe('node.process_list', (json) => {
    const snapshot = safeParseSnapshot(json as string);
    if (snapshot) snapshotChannel.emit(snapshot);
  });
  bridgeHub.subscribe('node.process_kill_result', (json) => {
    const result = safeParseKillResult(json as string);
    if (result) killResultChannel.emit(result);
  });
}

const ensureInstalled = (): void => {
  if (typeof window === 'undefined') return;
  installNodeProcessDispatchers();
};

// 自动安装 dispatchers
ensureInstalled();

export function subscribeNodeProcesses(listener: SnapshotListener): () => void {
  return snapshotChannel.subscribe(listener);
}

export function subscribeNodeProcessKillResult(listener: KillResultListener): () => void {
  return killResultChannel.subscribe(listener);
}

/** Request the latest snapshot from Java. The response arrives via `window.updateNodeProcesses`. */
export function fetchNodeProcesses(): void {
  sendBridgeEvent('get_node_processes');
}

/** Ask the backend to kill a single process by PID. */
export function killNodeProcess(pid: number, id?: string): void {
  sendBridgeEvent('kill_node_process', JSON.stringify(id ? { pid, id } : { pid }));
}

/** Ask the backend to kill every detected orphan process. */
export function killAllOrphanProcesses(): void {
  sendBridgeEvent('kill_all_orphans');
}

/** Ask the backend to restart the daemon owning the given PID (falls back to plain kill on miss). */
export function restartNodeDaemon(pid: number): void {
  sendBridgeEvent('restart_node_daemon', JSON.stringify({ pid }));
}
