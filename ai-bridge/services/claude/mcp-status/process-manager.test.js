import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import {
  createProcessHandlers,
  safeKillProcess,
  sendInitializeRequest,
} from './process-manager.js';

function createChild() {
  const child = new EventEmitter();
  const createStream = () => ({
    destroyed: false,
    destroy() {
      this.destroyed = true;
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

test('safeKillProcess sends SIGTERM even when child.killed is already true', () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');
  child.exitCode = 0;
  child.emit('exit', 0);

  assert.deepEqual(child.signals, ['SIGTERM']);
  assert.equal(child.stdin.destroyed, true);
  assert.equal(child.stdout.destroyed, true);
  assert.equal(child.stderr.destroyed, true);
});

test('safeKillProcess escalates to SIGKILL when the process remains alive', async () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');

  await new Promise((resolve) => setTimeout(resolve, 550));

  assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
});

test('safeKillProcess cancels SIGKILL escalation after close', async () => {
  const child = createChild();
  safeKillProcess(child, 'test-server');
  child.exitCode = 0;
  child.emit('close', 0);

  await new Promise((resolve) => setTimeout(resolve, 550));

  assert.deepEqual(child.signals, ['SIGTERM']);
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
