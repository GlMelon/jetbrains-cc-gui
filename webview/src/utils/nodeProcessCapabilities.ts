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

import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
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

// 创建回调通道
const snapshotChannel = createCallbackChannel<NodeProcessSnapshot>({
  name: 'nodeProcess:snapshot',
});

const killResultChannel = createCallbackChannel<NodeProcessKillResult>({
  name: 'nodeProcess:killResult',
});



export function subscribeNodeProcesses(listener: SnapshotListener): () => void {
  return snapshotChannel.subscribe(listener);
}

export function subscribeNodeProcessKillResult(listener: KillResultListener): () => void {
  return killResultChannel.subscribe(listener);
}

/** Request the latest snapshot from Java. The response arrives via `window.updateNodeProcesses`. */
export function fetchNodeProcesses(): void {
  sendAction(UPSTREAM.GET_NODE_PROCESSES);
}

/** Ask the backend to kill a single process by PID. */
export function killNodeProcess(pid: number, id?: string): void {
  sendAction(UPSTREAM.KILL_NODE_PROCESS, JSON.stringify(id ? { pid, id } : { pid }));
}

/** Ask the backend to kill every detected orphan process. */
export function killAllOrphanProcesses(): void {
  sendAction(UPSTREAM.KILL_ALL_ORPHANS);
}

/** Ask the backend to restart the daemon owning the given PID (falls back to plain kill on miss). */
export function restartNodeDaemon(pid: number): void {
  sendAction(UPSTREAM.RESTART_NODE_DAEMON, JSON.stringify({ pid }));
}
