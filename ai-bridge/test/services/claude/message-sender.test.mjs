import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCliArgs,
  normalizeReasoningEffort,
} from '../../../services/claude/message-sender.js';

test('normalizeReasoningEffort passes low/medium/high through verbatim', () => {
  assert.equal(normalizeReasoningEffort('low'), 'low');
  assert.equal(normalizeReasoningEffort('medium'), 'medium');
  assert.equal(normalizeReasoningEffort('high'), 'high');
});

test('normalizeReasoningEffort passes xhigh/max through verbatim (no downgrade to high)', () => {
  // Aligned with the Java CLI path: tier availability is negotiated by the
  // CLI per model, so xhigh/max are forwarded as-is.
  assert.equal(normalizeReasoningEffort('xhigh'), 'xhigh');
  assert.equal(normalizeReasoningEffort('max'), 'max');
});

test('normalizeReasoningEffort drops unknown values and non-strings', () => {
  assert.equal(normalizeReasoningEffort('ultra'), '');
  assert.equal(normalizeReasoningEffort(''), '');
  assert.equal(normalizeReasoningEffort(null), '');
  assert.equal(normalizeReasoningEffort(undefined), '');
  assert.equal(normalizeReasoningEffort(42), '');
});

test('normalizeReasoningEffort trims and lowercases input', () => {
  assert.equal(normalizeReasoningEffort('  HIGH '), 'high');
  assert.equal(normalizeReasoningEffort('Max'), 'max');
});

test('buildCliArgs emits --effort (aligned with Java CliConstants.ARG_EFFORT)', () => {
  const args = buildCliArgs({ message: 'hi', reasoningEffort: 'xhigh' });
  const flagIndex = args.indexOf('--effort');
  assert.notEqual(flagIndex, -1);
  assert.equal(args[flagIndex + 1], 'xhigh');
  assert.equal(args.includes('--reasoning-effort'), false);
});

test('buildCliArgs omits --effort when reasoningEffort is empty', () => {
  assert.equal(buildCliArgs({ message: 'hi' }).includes('--effort'), false);
  assert.equal(buildCliArgs({ message: 'hi', reasoningEffort: '' }).includes('--effort'), false);
});
