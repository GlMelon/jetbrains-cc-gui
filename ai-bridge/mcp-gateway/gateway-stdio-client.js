#!/usr/bin/env node
// gateway-stdio-client.js — 注入到 provider CLI 的 MCP server 包装(指向本地 gateway HTTP)。
//
// 30s 根因消除(详见 gateway-http-client.js 文档):
//   ① Opt1: state file 缺失/不可读 → 清晰 stderr [melon-gateway-down] + 显式 exit(1)(不再裸抛挂起);
//   ② Opt2: HTTP 请求走 GatewayHttpClient(默认 5s 超时,挂死 TCP 不再拖 30s);
//   ③ Opt2: tools/list 不可达时降级返 {tools:[]}(对话继续,用户失去 MCP 工具但不阻塞),
//           stderr [melon-gateway-down] 供 Java 侧上行前端 toast(见 GatewayDownMatcher)。
import { FramedReader } from './framing.js';
import { GatewayMcpServer } from './mcp-server.js';
import { RevisionStore } from './revision-store.js';
import { ToolRouter } from './tool-router.js';
import { readStateFile } from './state-file.js';
import { GatewayHttpClient, runToolsList } from './gateway-http-client.js';

const args = parseArgs(process.argv.slice(2));
const stateFile = args['state-file'];
const revision = Number(args.revision || 0);

// Opt1: state file 缺失(gateway 未启动/被清/路径错)→ 清晰 stderr + 显式 exit(1)。
// 标记前缀 [melon-gateway-down] 供 Java 侧识别后上行前端 toast。原裸抛 ENOENT 不可观测。
let state;
try {
  state = readStateFile(stateFile);
} catch (error) {
  process.stderr.write(`[melon-gateway-down] state file unreadable (${stateFile}): ${error.code || error.message}\n`);
  process.exit(1);
}

// Opt2: HTTP 请求集中走 GatewayHttpClient(默认 5s 超时 + AbortController,范式 transport/http-client.js)。
const httpClient = new GatewayHttpClient({ port: state.port, token: state.token });

class RuntimeProxy {
  async call(name, toolArgs) {
    return httpClient.post('/runtime/tools/call', { revision, name, arguments: toolArgs });
  }
}

const revisionStore = new RevisionStore(1);
const toolRouter = new ToolRouter(new Map([['runtime:proxy', new RuntimeProxy()]]));
toolRouter.call = async (name, toolArgs) => httpClient.post('/runtime/tools/call', { revision, name, arguments: toolArgs });

const server = new GatewayMcpServer({
  revisionStore,
  toolRouter,
  revision,
});

server.handle = async function handle(message, output) {
  // Opt2: tools/list 走 runToolsList——gateway 不可达时降级返空工具(对话继续,不再挂 30s),
  // 不再返 JSON-RPC error 让 provider 标记失败。stderr 标记供 Java toast。
  if (await runToolsList({ httpClient, revision, message, output })) {
    return;
  }
  await GatewayMcpServer.prototype.handle.call(this, message, output);
};

const reader = new FramedReader(process.stdin);
// reader 探测客户端(provider)帧格式后同步到 stdout:opencode=ndjson(MCP spec 标准)、
// codex=lsp(Content-Length)。writeMessage 据此自适应响应帧格式,两端各得其所不回归。
// 修 opencode gateway 首请求 30s 握手超时(原 framing 只认 LSP,opencode 的 NDJSON
// initialize 永远进不了解析路径)。详见 framing.js 文档。
reader.on('message', (message) => {
  process.stdout.__mcpFrameFormat = reader.lastFormat || 'ndjson';
  server.handle(message, process.stdout);
});

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
