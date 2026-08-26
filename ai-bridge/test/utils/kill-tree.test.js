import assert from 'node:assert/strict';
import test from 'node:test';

import { killChildTree } from '../../utils/kill-tree.js';

function createFakeChild(overrides = {}) {
  const signals = [];
  return {
    pid: undefined,
    exitCode: null,
    signalCode: null,
    killed: false,
    signals,
    kill(signal) {
      signals.push(signal);
      return true;
    },
    ...overrides,
  };
}

test('killChildTree is a no-op for null/undefined children', () => {
  assert.doesNotThrow(() => killChildTree(null));
  assert.doesNotThrow(() => killChildTree(undefined));
});

test('killChildTree skips children that already exited', () => {
  const byExitCode = createFakeChild({ exitCode: 0 });
  killChildTree(byExitCode, 'test');
  assert.deepEqual(byExitCode.signals, []);

  const bySignal = createFakeChild({ signalCode: 'SIGTERM' });
  killChildTree(bySignal, 'test');
  assert.deepEqual(bySignal.signals, []);
});

test('killChildTree still kills when child.killed is true but process has not exited', () => {
  // Node sets killed=true after the first kill() call even while the process is
  // still alive, so the guard must be exit-based, not killed-based.
  const child = createFakeChild({ killed: true });
  killChildTree(child, 'test');
  // Fake child has no pid: both platforms fall back to a direct SIGTERM.
  assert.deepEqual(child.signals, ['SIGTERM']);
});

test('killChildTree falls back to child.kill when no pid is available', () => {
  const child = createFakeChild();
  killChildTree(child, 'test');
  assert.deepEqual(child.signals, ['SIGTERM']);
});
