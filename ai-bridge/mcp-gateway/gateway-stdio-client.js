#!/usr/bin/env node
import http from 'node:http';
import { FramedReader, writeMessage } from './framing.js';
import { GatewayMcpServer } from './mcp-server.js';
import { RevisionStore } from './revision-store.js';
import { ToolRouter } from './tool-router.js';
import { readStateFile } from './state-file.js';

const args = parseArgs(process.argv.slice(2));
const stateFile = args['state-file'];
const revision = Number(args.revision || 0);
const state = readStateFile(stateFile);
const token = state.token;

class RuntimeProxy {
  async call(name, toolArgs) {
    return post('/runtime/tools/call', { revision, name, arguments: toolArgs });
  }
}

const revisionStore = new RevisionStore(1);
const toolRouter = new ToolRouter(new Map([['runtime:proxy', new RuntimeProxy()]]));
toolRouter.call = async (name, toolArgs) => post('/runtime/tools/call', { revision, name, arguments: toolArgs });

const server = new GatewayMcpServer({
  revisionStore,
  toolRouter,
  revision,
});

server.handle = async function handle(message, output) {
  if (message?.method === 'tools/list') {
    try {
      const result = await get(`/runtime/tools/list?revision=${encodeURIComponent(revision)}`);
      writeMessage(output, { jsonrpc: '2.0', id: message.id, result });
    } catch (error) {
      writeMessage(output, { jsonrpc: '2.0', id: message.id, error: { code: -32000, message: error.message } });
    }
    return;
  }
  await GatewayMcpServer.prototype.handle.call(this, message, output);
};

const reader = new FramedReader(process.stdin);
reader.on('message', (message) => server.handle(message, process.stdout));

function request(method, path, body) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const req = http.request({
      host: '127.0.0.1',
      port: state.port,
      path,
      method,
      headers: {
        authorization: `Bearer ${token}`,
        ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}),
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`Gateway HTTP ${res.statusCode}`));
          return;
        }
        resolve(text ? JSON.parse(text) : {});
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

const get = (path) => request('GET', path);
const post = (path, body) => request('POST', path, body);

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
