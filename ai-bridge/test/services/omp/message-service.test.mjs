import test from 'node:test';
import assert from 'node:assert/strict';

import { buildOmpArgs } from '../../../services/omp/message-service.js';

function thinkingLevelOf(args) {
  const index = args.indexOf('--thinking');
  return index === -1 ? null : args[index + 1];
}

test('buildOmpArgs passes low/medium/high/xhigh/max verbatim into --thinking', () => {
  for (const level of ['low', 'medium', 'high', 'xhigh', 'max']) {
    const args = buildOmpArgs({ message: 'hi', reasoningEffort: level });
    assert.equal(thinkingLevelOf(args), level, `level ${level}`);
  }
});

test('buildOmpArgs omits --thinking for unknown effort values', () => {
  assert.equal(thinkingLevelOf(buildOmpArgs({ message: 'hi', reasoningEffort: 'ultra' })), null);
  assert.equal(thinkingLevelOf(buildOmpArgs({ message: 'hi', reasoningEffort: '' })), null);
  assert.equal(thinkingLevelOf(buildOmpArgs({ message: 'hi' })), null);
});

test('buildOmpArgs omits --thinking when thinkingOutputEnabled is off', () => {
  const args = buildOmpArgs({
    message: 'hi',
    reasoningEffort: 'high',
    thinkingOutputEnabled: false,
  });
  assert.equal(thinkingLevelOf(args), null);
  // 'off' itself is a whitelist level but is also suppressed by the switch.
  const offArgs = buildOmpArgs({
    message: 'hi',
    reasoningEffort: 'off',
    thinkingOutputEnabled: false,
  });
  assert.equal(thinkingLevelOf(offArgs), null);
});

test('buildOmpArgs normalizes case/whitespace of the effort level', () => {
  const args = buildOmpArgs({ message: 'hi', reasoningEffort: '  MAX ' });
  assert.equal(thinkingLevelOf(args), 'max');
});

test('buildOmpArgs maps protocol none to wire off for --thinking', () => {
  // Protocol vocabulary says "none"; the omp CLI --thinking vocabulary says "off".
  assert.equal(thinkingLevelOf(buildOmpArgs({ message: 'hi', reasoningEffort: 'none' })), 'off');
  assert.equal(thinkingLevelOf(buildOmpArgs({ message: 'hi', reasoningEffort: ' NONE ' })), 'off');
});
