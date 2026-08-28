/**
 * Tests for partitionCircuitSkipped in index.js (circuit-open skip protocol).
 *
 * Contract with the Java side (McpVerifyCircuitBreaker):
 * - Skipped servers must NOT be verified (no spawn) and must carry the
 *   [circuit-open] marker in their error field.
 * - Non-skipped servers must pass through untouched so one broken server
 *   never affects the verification of healthy ones.
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { CIRCUIT_SKIP_MARKER, partitionCircuitSkipped } from './index.js';

test('partitionCircuitSkipped passes everything through when skip list is empty/null', () => {
  const enabled = [
    { name: 'a', config: { command: 'node' } },
    { name: 'b', config: { command: 'npx' } },
  ];

  for (const skip of [null, undefined, [], ['']]) {
    const { toVerify, skippedResults } = partitionCircuitSkipped(enabled, skip);
    assert.equal(toVerify.length, 2);
    assert.deepEqual(skippedResults, []);
    assert.deepEqual(toVerify, enabled);
  }
});

test('partitionCircuitSkipped splits skipped servers out of verification', () => {
  const enabled = [
    { name: 'healthy', config: { command: 'node' } },
    { name: 'broken', config: { command: 'cmd' } },
    { name: 'also-healthy', config: { command: 'npx' } },
  ];

  const { toVerify, skippedResults } = partitionCircuitSkipped(enabled, ['broken']);

  assert.deepEqual(toVerify, [
    { name: 'healthy', config: { command: 'node' } },
    { name: 'also-healthy', config: { command: 'npx' } },
  ]);

  assert.equal(skippedResults.length, 1);
  assert.equal(skippedResults[0].name, 'broken');
  assert.equal(skippedResults[0].status, 'failed');
  assert.ok(skippedResults[0].error.includes(CIRCUIT_SKIP_MARKER));
});

test('partitionCircuitSkipped skips every listed server and only those', () => {
  const enabled = [
    { name: 'a', config: {} },
    { name: 'b', config: {} },
    { name: 'c', config: {} },
  ];

  const { toVerify, skippedResults } = partitionCircuitSkipped(enabled, ['a', 'c']);

  assert.deepEqual(toVerify.map((s) => s.name), ['b']);
  assert.deepEqual(skippedResults.map((r) => r.name).sort(), ['a', 'c']);
  for (const result of skippedResults) {
    assert.equal(result.status, 'failed');
    assert.ok(result.error.startsWith(CIRCUIT_SKIP_MARKER));
  }
});
