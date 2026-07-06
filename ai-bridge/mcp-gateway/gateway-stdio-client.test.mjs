// gateway-stdio-client.test.mjs — 入口脚本 ENOENT 烟雾测试(原零测试,本次补齐)。
//
// Opt1 核心回归保护:state file 缺失/不可读时,脚本必须显式 exit(1) + 写 [melon-gateway-down]
// stderr 标记(供 Java 侧 GatewayDownMatcher 上行前端 toast),而非裸抛 ENOENT 不可观测。
// tools/list 降级语义由 gateway-http-client.test.mjs 的 runToolsList 纯函数测试覆盖;
// 完整 MCP 握手集成(provider spawn + stdin/stdout 交互)留端到端手测。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const script = path.join(__dirname, 'gateway-stdio-client.js');

test('ENOENT: state file 不存在 → exit 1 + stderr 含 [melon-gateway-down]', () => {
  const stateFile = path.join(os.tmpdir(), `melon-gateway-nonexistent-${process.pid}.json`);
  const result = spawnSync(process.execPath, [script, '--state-file', stateFile, '--revision', '0'], {
    encoding: 'utf8',
    timeout: 5000,
  });
  assert.equal(result.status, 1, 'state file 缺失应 exit(1),而非挂起或裸抛');
  assert.match(result.stderr, /\[melon-gateway-down\] state file unreadable/);
  assert.match(result.stderr, /ENOENT/, '应暴露底层错误码供排查');
});

test('ENOENT: stderr 含 state file 路径,便于定位是哪个 tab 的 state', () => {
  const stateFile = path.join(os.tmpdir(), `melon-gateway-path-trace-${process.pid}.json`);
  const result = spawnSync(process.execPath, [script, '--state-file', stateFile, '--revision', '0'], {
    encoding: 'utf8',
    timeout: 5000,
  });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes(stateFile), 'stderr 须含完整 state file 路径');
});
