import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

import { shouldYieldToNativeClassifier } from '../../hooks/pre-tool-use-hook.js';

const HOOK_SCRIPT = fileURLToPath(new URL('../../hooks/pre-tool-use-hook.js', import.meta.url));

/**
 * Run the hook as a fresh subprocess (mirrors how the Claude CLI invokes it),
 * feeding `payload` as stdin JSON in an isolated permission dir.
 */
function runHook(payload) {
  const permissionDir = mkdtempSync(join(tmpdir(), 'ccg-hook-test-'));
  const result = spawnSync(process.execPath, [HOOK_SCRIPT], {
    input: JSON.stringify(payload),
    encoding: 'utf8',
    env: {
      ...process.env,
      CLAUDE_PERMISSION_DIR: permissionDir,
      CLAUDE_SESSION_ID: 'hook-test-session',
      CLAUDE_PERMISSION_SAFETY_NET_MS: '1000',
    },
    timeout: 15000,
  });
  const stdoutJson = JSON.parse(result.stdout.trim());
  return { result, stdoutJson, permissionDir };
}

// ---------- shouldYieldToNativeClassifier (pure) ----------

test('auto mode yields to the CLI native classifier', () => {
  assert.equal(shouldYieldToNativeClassifier({ permission_mode: 'auto' }), true);
});

test('non-auto modes do not yield (bridge dialog behavior unchanged)', () => {
  for (const mode of ['default', 'plan', 'acceptEdits', 'bypassPermissions']) {
    assert.equal(shouldYieldToNativeClassifier({ permission_mode: mode }), false, `mode ${mode} must not yield`);
  }
});

test('missing / unknown permission_mode fails safe to no-yield (older CLIs keep bridge behavior)', () => {
  assert.equal(shouldYieldToNativeClassifier({}), false);
  assert.equal(shouldYieldToNativeClassifier({ permission_mode: '' }), false);
  assert.equal(shouldYieldToNativeClassifier({ permission_mode: 'bogus-mode' }), false);
  assert.equal(shouldYieldToNativeClassifier(null), false);
  assert.equal(shouldYieldToNativeClassifier(undefined), false);
});

// ---------- subprocess integration ----------

test('auto mode: hook emits {continue:true} without touching the IPC permission dir', () => {
  const { result, stdoutJson, permissionDir } = runHook({
    tool_name: 'Bash',
    tool_input: { command: 'rm -rf /' },
    permission_mode: 'auto',
  });

  assert.equal(result.status, 0, `hook exited ${result.status}, stderr: ${result.stderr}`);
  assert.deepEqual(stdoutJson, { continue: true });
  // Yield happens before canUseTool: no request-<sid>.json may be written,
  // so even a dangerous command goes straight to the native classifier.
  const ipcFiles = readdirSync(permissionDir).filter((name) => name.startsWith('request-'));
  assert.deepEqual(ipcFiles, []);
});

test('missing tool_name still fails closed with a deny payload', () => {
  const { result, stdoutJson } = runHook({ tool_input: {}, permission_mode: 'auto' });

  assert.equal(result.status, 0);
  assert.equal(stdoutJson?.hookSpecificOutput?.permissionDecision, 'deny');
  assert.match(stdoutJson?.hookSpecificOutput?.permissionDecisionReason ?? '', /missing tool_name/);
});

test('default mode: dangerous-path deny rail still fires inside the bridge (no yield)', () => {
  // cat ~/.ssh/id_rsa via Bash hits the canUseTool dangerous-path deny BEFORE any
  // IPC round trip, proving non-auto modes keep the existing interception chain.
  const { result, stdoutJson } = runHook({
    tool_name: 'Bash',
    tool_input: { command: 'cat ~/.ssh/id_rsa' },
    permission_mode: 'default',
  });

  assert.equal(result.status, 0, `hook exited ${result.status}, stderr: ${result.stderr}`);
  assert.equal(stdoutJson?.hookSpecificOutput?.permissionDecision, 'deny');
  assert.match(stdoutJson?.hookSpecificOutput?.permissionDecisionReason ?? '', /sensitive path/i);
});
