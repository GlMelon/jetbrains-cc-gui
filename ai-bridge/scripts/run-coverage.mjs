#!/usr/bin/env node
/**
 * run-coverage.mjs — ai-bridge c8 覆盖率采集入口(docs/comprehensive-optimization-directions.md §11.5 / §T1)。
 *
 * 核心矛盾:c8 二进制在 ai-bridge/node_modules,但 ai-bridge 测试用 path.resolve('.')
 * (cwd=仓库根)解析路径,必须从仓库根运行。本脚本锚定仓库根后以 c8 为 node 父进程
 * spawn,同时满足:① c8 搜索文件基准 ② node --test 展开 glob 基准 ③ 测试内 cwd 解析。
 *
 * 绕过 .bin/c8 shim(Windows .cmd / Linux sh shebang 跨平台坑):直接 node <c8.js>,
 * 不带 shell,保留 glob 字面量给 Node 21+ 自展。c8 自行注入并管理 NODE_V8_COVERAGE。
 *
 * 用法:node ai-bridge/scripts/run-coverage.mjs   (cwd 无关,可从仓库任意位置调用)
 */
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const AI_BRIDGE_DIR = resolve(__dirname, '..');   // ai-bridge/
const REPO_ROOT = resolve(AI_BRIDGE_DIR, '..');   // 仓库根
// 直跑 c8.js,绕过 .bin shim 跨平台坑
const C8_JS = join(AI_BRIDGE_DIR, 'node_modules', 'c8', 'bin', 'c8.js');
const C8RC = join(AI_BRIDGE_DIR, '.c8rc.json');

const result = spawnSync(process.execPath, [
  C8_JS,
  '--config', C8RC,
  'node', '--test', 'ai-bridge/test/**/*.test.js',
], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
  env: { ...process.env },
});

process.exit(result.status ?? 1);
