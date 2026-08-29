import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import {
  createProcessHandlers,
  safeKillProcess,
  sendInitializeRequest,
} from '../../../../services/claude/mcp-status/process-manager.js';

function createChild() {
  const child = new EventEmitter();
  const createStream = () => ({
    destroyed: false,
    writableEnded: false,
    destroy() {
      this.destroyed = true;
    },
    end() {
      this.writableEnded = true;
    },
  });
  child.stdin = createStream();
  child.stdout = createStream();
  child.stderr = createStream();
  child.exitCode = null;
  child.signalCode = null;
  child.killed = true;
  child.signals = [];
  child.kill = (signal) => {
    child.signals.push(signal);
    return true;
  };
  return child;
}

test('safeKillProcess sends SIGTERM even when child.killed is already true', async () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');

  // Signals are deferred until the stdin-EOF grace window elapses (upstream
  // #1721). A live child must still be signalled even though Node already
  // flagged it as killed (exit-based guard, see kill-tree.js).
  await new Promise((resolve) => setTimeout(resolve, 550));

  assert.deepEqual(child.signals, ['SIGTERM']);
  // stdin gets an EOF (end()), stdio pipes are no longer force-destroyed.
  assert.equal(child.stdin.writableEnded, true);
});

test('safeKillProcess escalates to SIGKILL when the process remains alive', async () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');

  await new Promise((resolve) => setTimeout(resolve, 550));

  if (process.platform === 'win32') {
    // Windows uses taskkill /F /T via killChildTree (already a forced tree
    // kill; the fake child has no pid so it hits the child.kill fallback).
    // No SIGKILL escalation timer exists on this platform.
    assert.deepEqual(child.signals, ['SIGTERM']);
  } else {
    assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
  }
});

test('safeKillProcess skips signalling when the child exits during the grace window', async () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');
  // Child exits on its own after stdin EOF — the deferred signal pass must
  // notice the exit (exit-based guard) and never fire, let alone escalate.
  child.exitCode = 0;
  child.emit('close', 0);

  await new Promise((resolve) => setTimeout(resolve, 550));

  assert.deepEqual(child.signals, []);
});

test('createProcessHandlers bounds retained stdout and stderr', () => {
  const handlers = createProcessHandlers({
    serverName: 'test-server',
    finalize() {},
  });
  const oversized = 'a'.repeat(1024 * 1024);

  handlers.stdout.onData(Buffer.from(oversized));
  handlers.stdout.onData(Buffer.from('stdout-tail'));
  handlers.stderr.onData(Buffer.from(oversized));
  handlers.stderr.onData(Buffer.from('stderr-tail'));

  assert.equal(handlers.getStdout().length, 1024 * 1024);
  assert.equal(handlers.getStderr().length, 1024 * 1024);
  assert.equal(handlers.getStdout().endsWith('stdout-tail'), true);
  assert.equal(handlers.getStderr().endsWith('stderr-tail'), true);
});

test('sendInitializeRequest handles stream error and callback error once', async () => {
  const stdin = new EventEmitter();
  stdin.destroyed = false;
  stdin.writable = true;
  stdin.write = (_payload, callback) => {
    queueMicrotask(() => {
      const error = new Error('EPIPE');
      stdin.emit('error', error);
      callback(error);
    });
    return true;
  };

  assert.doesNotThrow(() => sendInitializeRequest({ stdin }, 'test-server'));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(stdin.listenerCount('error'), 0);
});
