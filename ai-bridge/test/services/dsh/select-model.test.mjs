import test from 'node:test';
import assert from 'node:assert/strict';

import { selectModel } from '../../../services/dsh/session.js';
import { applyModelSelection } from '../../../services/dsh/message-service.js';

function recordingClient({ fail = false } = {}) {
  const calls = [];
  return {
    calls,
    async call(method, payload) {
      calls.push({ method, payload });
      if (fail) throw new Error('host rejected selectModel');
      return {};
    },
  };
}

test('selectModel RPC payload carries reasoningEffort', async () => {
  const client = recordingClient();
  await selectModel(client, 'sess-1', 'anthropic', 'claude-opus', 'max');
  assert.deepEqual(client.calls, [{
    method: 'session.selectModel',
    payload: { sessionId: 'sess-1', provider: 'anthropic', model: 'claude-opus', reasoningEffort: 'max' },
  }]);
});

test('selectModel omits reasoningEffort when empty/blank', async () => {
  const client = recordingClient();
  await selectModel(client, 'sess-1', 'anthropic', 'claude-opus', '  ');
  await selectModel(client, 'sess-1', 'anthropic', 'claude-opus');
  for (const { payload } of client.calls) {
    assert.equal('reasoningEffort' in payload, false);
  }
});

test('applyModelSelection forwards reasoningEffort with an explicit provider/model tuple', async () => {
  const client = recordingClient();
  await applyModelSelection(client, 'sess-1', 'anthropic/claude-opus', 'xhigh');
  assert.deepEqual(client.calls, [{
    method: 'session.selectModel',
    payload: { sessionId: 'sess-1', provider: 'anthropic', model: 'claude-opus', reasoningEffort: 'xhigh' },
  }]);
});

test('applyModelSelection does not call selectModel without an explicit tuple', async () => {
  // Intentional behavior: selectModel is only issued when the composer picked
  // an explicit provider/model tuple. Changing effort alone (no model tuple)
  // does not reach the host — this test locks that in.
  for (const model of ['', 'auto', 'default', 'dsh-default']) {
    const client = recordingClient();
    await applyModelSelection(client, 'sess-1', model, 'high');
    assert.deepEqual(client.calls, [], `model=${JSON.stringify(model)}`);
  }
});

test('applyModelSelection swallows selectModel failures (continues with session model)', async () => {
  const client = recordingClient({ fail: true });
  await applyModelSelection(client, 'sess-1', 'anthropic/claude-opus', 'high');
  assert.equal(client.calls.length, 1);
});
