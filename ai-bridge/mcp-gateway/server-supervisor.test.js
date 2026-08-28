/**
 * Tests for backoffDelayMs (ServerSupervisor exponential backoff).
 *
 * Before this fix, BACKOFF was merely a health label: refresh() would re-spawn
 * a failing server immediately on every call (zero delay, zero cap). Now a
 * failing server enters an exponential cooldown so config churn / gateway reload
 * cannot produce a spawn storm against broken servers.
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { backoffDelayMs } from './server-supervisor.js';

test('backoffDelayMs returns 0 for non-positive failure counts', () => {
  assert.equal(backoffDelayMs(0), 0);
  assert.equal(backoffDelayMs(-1), 0);
});

test('backoffDelayMs grows exponentially: 1s, 2s, 4s, 8s ...', () => {
  assert.equal(backoffDelayMs(1), 1_000);
  assert.equal(backoffDelayMs(2), 2_000);
  assert.equal(backoffDelayMs(3), 4_000);
  assert.equal(backoffDelayMs(4), 8_000);
  assert.equal(backoffDelayMs(5), 16_000);
});

test('backoffDelayMs caps at 5 minutes', () => {
  // 2^29 * 1000 = ~560s > 300s cap; failureCount=10 deep into the cap
  assert.equal(backoffDelayMs(10), 5 * 60_000);
  assert.equal(backoffDelayMs(100), 5 * 60_000);
});
