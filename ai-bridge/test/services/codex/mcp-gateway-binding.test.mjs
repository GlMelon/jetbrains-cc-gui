import test from 'node:test';
import assert from 'node:assert/strict';
import { buildCodexGatewayConfig, codexGatewayRevision, applyCodexGateway } from '../../../services/codex/mcp-gateway-binding.js';

// buildCodexGatewayConfig 把 SDK binding（来自 Java McpGatewaySdkBinding 序列化）
// 翻译成 Codex SDK 的 config override（mcp_servers.melon_gateway），由 @openai/codex-sdk
// 展平成 --config mcp_servers.melon_gateway.command="node" ... 注入底层 Codex CLI。
// 与 Claude 的 buildGatewayMcpServers（产出 mcpServers option）对称，但格式遵循
// Codex config.toml 表语义（command + args + enabled + startup_timeout_sec，无 type 字段）。

test('buildCodexGatewayConfig returns null when binding is null or disabled', () => {
  assert.equal(buildCodexGatewayConfig(null), null);
  assert.equal(
    buildCodexGatewayConfig({ enabled: false, ready: false, revision: 0, command: [] }),
    null
  );
});

test('buildCodexGatewayConfig returns null when enabled but not ready', () => {
  const binding = {
    enabled: true,
    ready: false,
    revision: 5,
    command: ['node', 'gateway-stdio-client.js', '--state-file', '/tmp/s.json', '--revision', '5'],
  };
  assert.equal(buildCodexGatewayConfig(binding), null);
});

test('buildCodexGatewayConfig returns null when command has fewer than 2 parts', () => {
  assert.equal(
    buildCodexGatewayConfig({ enabled: true, ready: true, revision: 5, command: ['node'] }),
    null
  );
  assert.equal(
    buildCodexGatewayConfig({ enabled: true, ready: true, revision: 5, command: [] }),
    null
  );
});

test('buildCodexGatewayConfig builds a mcp_servers.melon_gateway config override from command', () => {
  const binding = {
    enabled: true,
    ready: true,
    revision: 7,
    command: ['node', 'gateway-stdio-client.js', '--state-file', '/tmp/s.json', '--revision', '7'],
  };
  const config = buildCodexGatewayConfig(binding);
  assert.deepEqual(config, {
    mcp_servers: {
      melon_gateway: {
        command: 'node',
        args: ['gateway-stdio-client.js', '--state-file', '/tmp/s.json', '--revision', '7'],
        enabled: true,
        startup_timeout_sec: 1,
      },
    },
  });
});

test('codexGatewayRevision returns revision when binding usable', () => {
  const binding = {
    enabled: true,
    ready: true,
    revision: 9,
    command: ['node', 'gateway-stdio-client.js', '--state-file', '/x', '--revision', '9'],
  };
  assert.equal(codexGatewayRevision(binding), 9);
});

test('codexGatewayRevision returns null when binding not usable', () => {
  assert.equal(codexGatewayRevision(null), null);
  assert.equal(
    codexGatewayRevision({ enabled: true, ready: false, revision: 9, command: ['node', 'x'] }),
    null
  );
});

// applyCodexGateway 把 gateway config override 合并进 codexOptions.config,保留原有字段
// (如 service_tier/features),由 message-service 在创建 codex 实例前调用。

test('applyCodexGateway leaves codexOptions unchanged when binding not usable', () => {
  const opts = { baseUrl: 'x', config: { service_tier: 'fast' } };
  // 返回原对象引用(无变化,无需新建)
  assert.equal(applyCodexGateway(opts, null), opts);
  assert.equal(
    applyCodexGateway(opts, { enabled: true, ready: false, revision: 1, command: ['node', 'x'] }),
    opts
  );
});

test('applyCodexGateway merges mcp_servers into config preserving existing fields', () => {
  const opts = { baseUrl: 'x', config: { features: { fast_mode: true }, service_tier: 'fast' } };
  const binding = {
    enabled: true,
    ready: true,
    revision: 3,
    command: ['node', 'client.js', '--state-file', '/s', '--revision', '3'],
  };
  const merged = applyCodexGateway(opts, binding);
  assert.deepEqual(merged.config, {
    features: { fast_mode: true },
    service_tier: 'fast',
    mcp_servers: {
      melon_gateway: {
        command: 'node',
        args: ['client.js', '--state-file', '/s', '--revision', '3'],
        enabled: true,
        startup_timeout_sec: 1,
      },
    },
  });
  // 原对象不被修改(不可变合并)
  assert.deepEqual(opts.config, { features: { fast_mode: true }, service_tier: 'fast' });
});

test('applyCodexGateway creates config when codexOptions has none', () => {
  const binding = {
    enabled: true,
    ready: true,
    revision: 1,
    command: ['node', 'c.js', '--revision', '1'],
  };
  const merged = applyCodexGateway({}, binding);
  assert.equal(merged.config.mcp_servers.melon_gateway.command, 'node');
});
