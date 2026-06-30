#!/usr/bin/env node
import { IpcServer } from './mcp-gateway/ipc-server.js';
import { RevisionStore } from './mcp-gateway/revision-store.js';
import { HealthStore } from './mcp-gateway/health-store.js';
import { writeStateFile, removeStateFile } from './mcp-gateway/state-file.js';

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

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    ipc.close();
    removeStateFile(stateFile);
    process.exit(0);
  });
}

process.on('exit', () => removeStateFile(stateFile));

function parseArgs(argv) {
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
