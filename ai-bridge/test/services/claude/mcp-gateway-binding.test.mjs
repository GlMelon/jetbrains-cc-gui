import test from 'node:test';
import assert from 'node:assert/strict';
import { buildGatewayMcpServers, gatewaySignatureMaterial } from '../../../services/claude/mcp-gateway-binding.js';

// buildGatewayMcpServers 把 SDK binding（来自 Java McpGatewaySdkBinding 序列化）
// 翻译成单个聚合 MCP 服务器 melon_gateway,与 CLI 模式对称。

test('buildGatewayMcpServers returns null when binding is null or disabled', () => {
  assert.equal(buildGatewayMcpServers(null), null);
  assert.equal(
    buildGatewayMcpServers({ enabled: false, ready: false, revision: 0, command: [] }),
    null
  );
});

test('buildGatewayMcpServers returns null when enabled but not ready', () => {
  const binding = {
    enabled: true,
    ready: false,
    revision: 5,
    command: ['node', 'client.js', '--state-file', 's', '--revision', '5'],
  };
  assert.equal(buildGatewayMcpServers(binding), null);
});

test('buildGatewayMcpServers builds a single melon_gateway stdio server from command', () => {
  const binding = {
    enabled: true,
    ready: true,
    revision: 5,
    command: [
      'node',
      '/ai-bridge/mcp-gateway/gateway-stdio-client.js',
      '--state-file',
      '/state.json',
      '--revision',
      '5',
    ],
  };
  const servers = buildGatewayMcpServers(binding);
  assert.deepEqual(Object.keys(servers), ['melon_gateway']);
  const server = servers.melon_gateway;
  assert.equal(server.type, 'stdio');
  assert.equal(server.command, 'node');
  assert.deepEqual(server.args, [
    '/ai-bridge/mcp-gateway/gateway-stdio-client.js',
    '--state-file',
    '/state.json',
    '--revision',
    '5',
  ]);
});

test('buildGatewayMcpServers returns null when command has fewer than 2 parts', () => {
  assert.equal(
    buildGatewayMcpServers({ enabled: true, ready: true, revision: 5, command: ['node'] }),
    null
  );
  assert.equal(
    buildGatewayMcpServers({ enabled: true, ready: true, revision: 5, command: [] }),
    null
  );
});

test('gatewaySignatureMaterial returns rev:N for a usable binding', () => {
  assert.equal(
    gatewaySignatureMaterial({ enabled: true, ready: true, revision: 5, command: [] }),
    'rev:5'
  );
});

test('gatewaySignatureMaterial returns null when binding is not usable', () => {
  assert.equal(gatewaySignatureMaterial(null), null);
  assert.equal(
    gatewaySignatureMaterial({ enabled: true, ready: false, revision: 5, command: [] }),
    null
  );
});
