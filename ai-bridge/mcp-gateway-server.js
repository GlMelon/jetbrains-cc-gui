#!/usr/bin/env node
// @ts-check
import { IpcServer } from './mcp-gateway/ipc-server.js';
import { RevisionStore } from './mcp-gateway/revision-store.js';
import { HealthStore } from './mcp-gateway/health-store.js';
import { writeStateFile } from './mcp-gateway/state-file.js';
import { installGatewayShutdown } from './mcp-gateway/shutdown-controller.js';

const args = parseArgs(process.argv.slice(2));
const stateFile = args['state-file'];
const token = args.token;

if (!stateFile || !token) {
  console.error('Missing --state-file or --token');
  process.exit(2);
}

const revisionStore = new RevisionStore();
const healthStore = new HealthStore();
const supervisors = new Map();
const ipc = new IpcServer({
  token,
  revisionStore,
  healthStore,
  supervisors,
  startedAt: Date.now(),
});

const port = await ipc.listen();
writeStateFile(stateFile, {
  port,
  pid: process.pid,
  token,
  projectPath: args['project-path'] ?? '',
});
console.log(`MCP Gateway listening on 127.0.0.1:${port}`);

installGatewayShutdown({ ipc, stateFile });

/**
 * 解析 `--key value` 形式的命令行参数。
 * @param {string[]} argv 进程参数切片(通常 `process.argv.slice(2)`)
 * @returns {Record<string, string>} key→value 映射;缺省值兜底为空串
 */
function parseArgs(argv) {
  /** @type {Record<string, string>} */
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      out[arg.slice(2)] = argv[i + 1] ?? '';
      i += 1;
    }
  }
  return out;
}
