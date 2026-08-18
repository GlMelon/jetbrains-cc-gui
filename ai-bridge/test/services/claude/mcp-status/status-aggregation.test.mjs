import assert from 'node:assert/strict';
import test from 'node:test';

import { verifyEnabledMcpServers } from '../../../../services/claude/mcp-status/index.js';

test('preserves successful MCP status when another verifier rejects', async () => {
  const enabledServers = [
    { name: 'healthy', config: { type: 'stdio' } },
    { name: 'broken', config: { type: 'stdio' } },
  ];

  const results = await verifyEnabledMcpServers(enabledServers, async (name) => {
    if (name === 'broken') {
      throw new Error('handshake failed');
    }
    return { name, status: 'connected' };
  });

  assert.deepEqual(results, [
    { name: 'healthy', status: 'connected' },
    { name: 'broken', status: 'failed', error: 'handshake failed' },
  ]);
});

test('normalizes non-Error rejection without changing input order', async () => {
  const enabledServers = [
    { name: 'first', config: {} },
    { name: 'second', config: {} },
    { name: 'third', config: {} },
  ];

  const results = await verifyEnabledMcpServers(enabledServers, (name) => {
    if (name === 'second') {
      return Promise.reject('connection refused');
    }
    return Promise.resolve({ name, status: 'connected' });
  });

  assert.deepEqual(results.map(({ name }) => name), ['first', 'second', 'third']);
  assert.deepEqual(results[1], {
    name: 'second',
    status: 'failed',
    error: 'connection refused',
  });
});
