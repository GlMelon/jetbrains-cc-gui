import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { getSessionMessages, getLatestUserMessage } from '../../../services/claude/session-service.js';

test('getSessionMessages returns an empty history when the session file is missing', async () => {
  const originalHome = process.env.HOME;
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-gui-claude-session-'));
  const output = [];
  const originalLog = console.log;

  process.env.HOME = tempHome;
  console.log = (message) => output.push(message);
  try {
    await getSessionMessages('missing-session', '/workspace/missing-history');
  } finally {
    console.log = originalLog;
    if (originalHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = originalHome;
    }
    fs.rmSync(tempHome, { recursive: true, force: true });
  }

  assert.equal(output.length, 1);
  assert.deepEqual(JSON.parse(output[0]), {
    success: true,
    messages: []
  });
});

/**
 * @param {string} name
 * @returns {string}
 */
function makeTempHome(name) {
  return fs.mkdtempSync(path.join(os.tmpdir(), name));
}

test('getLatestUserMessage reports messageHasCheckpoint when a snapshot row matches the user uuid', async () => {
  const result = await runWithTempHomeAndSession('session-checkpoint-yes', [
    { type: 'user', uuid: 'uuid-old', message: { role: 'user', content: 'older turn' } },
    { type: 'file-history-snapshot', messageId: 'uuid-new', snapshot: { messageId: 'uuid-new', trackedFileBackups: {} } },
    { type: 'user', uuid: 'uuid-new', message: { role: 'user', content: 'latest turn' } },
  ]);

  assert.equal(result.payload.success, true);
  assert.equal(result.payload.message.uuid, 'uuid-new');
  assert.equal(result.payload.messageHasCheckpoint, true);
});

test('getLatestUserMessage reports messageHasCheckpoint false without a matching snapshot row', async () => {
  const result = await runWithTempHomeAndSession('session-checkpoint-no', [
    { type: 'user', uuid: 'uuid-plain', message: { role: 'user', content: 'plain turn' } },
  ]);

  assert.equal(result.payload.success, true);
  assert.equal(result.payload.message.uuid, 'uuid-plain');
  assert.equal(result.payload.messageHasCheckpoint, false);
});

test('getLatestUserMessage matches snapshot rows written after the user row', async () => {
  const result = await runWithTempHomeAndSession('session-checkpoint-after', [
    { type: 'user', uuid: 'uuid-after', message: { role: 'user', content: 'after turn' } },
    { type: 'file-history-snapshot', messageId: 'uuid-after', snapshot: { messageId: 'uuid-after', trackedFileBackups: {} } },
  ]);

  assert.equal(result.payload.success, true);
  assert.equal(result.payload.message.uuid, 'uuid-after');
  assert.equal(result.payload.messageHasCheckpoint, true);
});

/**
 * getLatestUserMessage resolves the session file via os.homedir(), so the
 * temp home must be injected through the platform home variable (USERPROFILE
 * on Windows, HOME elsewhere) before importing is not needed — homedir() is
 * read lazily per call, but the realpath cache inside path-utils persists,
 * so this helper runs the whole case in a child process to stay hermetic.
 *
 * @param {string} sessionId
 * @param {object[]} rows JSONL rows to persist
 * @returns {Promise<{payload: any}>} parsed stdout payload
 */
async function runWithTempHomeAndSession(sessionId, rows) {
  const { spawnSync } = await import('node:child_process');
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-gui-claude-latest-'));
  const projectsDir = path.join(tempHome, '.claude', 'projects', '-workspace-latest');
  fs.mkdirSync(projectsDir, { recursive: true });
  fs.writeFileSync(
    path.join(projectsDir, `${sessionId}.jsonl`),
    rows.map((row) => JSON.stringify(row)).join('\n') + '\n',
    'utf8'
  );

  const script = `
    const rows = ${JSON.stringify(rows.length)};
    process.env.${process.platform === 'win32' ? 'USERPROFILE' : 'HOME'} = ${JSON.stringify(tempHome)};
    const { getLatestUserMessage } = await import(${JSON.stringify(
      new URL('../../../services/claude/session-service.js', import.meta.url).href
    )});
    await getLatestUserMessage(${JSON.stringify(sessionId)}, '/workspace/latest');
  `;

  try {
    const child = spawnSync(
      process.execPath,
      ['--input-type=module', '-e', script],
      { encoding: 'utf8', timeout: 30000 }
    );
    if (child.status !== 0) {
      assert.fail(`child process failed: ${child.stderr}`);
    }
    const jsonLine = child.stdout
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith('{') && line.endsWith('}'))
      .pop();
    assert.ok(jsonLine, `no JSON output from child: ${child.stdout}`);
    return { payload: JSON.parse(jsonLine) };
  } finally {
    fs.rmSync(tempHome, { recursive: true, force: true });
  }
}
