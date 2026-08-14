import test from 'node:test';
import assert from 'node:assert/strict';
import {
  abortCurrentCodexTurn,
  getCodexThreadCacheSizeForTest,
  resetCodexThreadCache,
} from '../../../services/codex/message-service.js';

// NOTE: filterCodexExperimentalJsonLines removed — SDK event filtering no longer needed in CLI mode.
// The isIgnorableCodexEventNoiseLine function is also removed as noise filtering is handled by CLI.

test('Codex thread cache reset helper clears cached entries state', () => {
  resetCodexThreadCache();
  assert.equal(getCodexThreadCacheSizeForTest(), 0);
  resetCodexThreadCache('non-existent-thread');
  assert.equal(getCodexThreadCacheSizeForTest(), 0);
});

test('Codex abort helper is a no-op when there is no active turn', async () => {
  assert.equal(await abortCurrentCodexTurn(), false);
});
