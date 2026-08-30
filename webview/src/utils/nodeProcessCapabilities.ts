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
import type {
  NodeProcessDiagnosticsPayloadWire,
  NodeProcessInfoPayloadWire,
  NodeProcessSnapshotPayloadWire,
  NodeProcessTotalsPayloadWire,
} from '../generated/protocol';
import { createCallbackChannel } from './createCallbackChannel';

export type NodeProcessKind = NodeProcessInfoPayloadWire['kind'];
export type NodeProcessInfo = NodeProcessInfoPayloadWire;
export type NodeProcessTotals = NodeProcessTotalsPayloadWire;
export type NodeProcessRuntimeDiagnostics = NodeProcessDiagnosticsPayloadWire;
export type NodeProcessSnapshot = NodeProcessSnapshotPayloadWire;

export interface NodeProcessKillResult {
  pid?: number;
  id?: string;
  success?: boolean;
  error?: string;
  killed?: number;
}

/** 后端 CLI_SESSION kill 拒绝码(daemon-mode §5.2),前端据此渲染保护提示。 */
export const KILL_PROTECTED_CLI_SESSION = 'cli_session_protected';

type SnapshotListener = (snapshot: NodeProcessSnapshot) => void;
type KillResultListener = (result: NodeProcessKillResult) => void;

// 创建回调通道
const snapshotChannel = createCallbackChannel<NodeProcessSnapshot>({
  name: 'nodeProcess:snapshot',
});

const killResultChannel = createCallbackChannel<NodeProcessKillResult>({
  name: 'nodeProcess:killResult',
});



export function installNodeProcessDispatchers(): void {
  (window as unknown as Record<string, unknown>).updateNodeProcesses = (json: string) => {
    try {
      const parsed = JSON.parse(json);
      if (parsed && typeof parsed === 'object' && Array.isArray(parsed.processes)) {
        snapshotChannel.emit(parsed as NodeProcessSnapshot);
      }
    } catch {
      // malformed JSON — silently drop
    }
  };
  (window as unknown as Record<string, unknown>).nodeProcessKillResult = (json: string) => {
    try {
      const parsed = JSON.parse(json);
      if (parsed && typeof parsed === 'object') {
        killResultChannel.emit(parsed as NodeProcessKillResult);
      }
    } catch {
      // malformed JSON — silently drop
    }
  };
}

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
