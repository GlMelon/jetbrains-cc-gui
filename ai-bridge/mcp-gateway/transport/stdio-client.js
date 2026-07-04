import { spawn } from 'node:child_process';
import { FramedReader, writeMessage } from '../framing.js';

let nextId = 1;

export class StdioMcpClient {
  // 单次 JSON-RPC 请求默认超时(ms)。单个 MCP server 挂起不应拖垮整个 gateway catalog
  // refresh(否则放大首次延迟)。config.request_timeout_ms 或 request 第三参可覆盖。
  static DEFAULT_REQUEST_TIMEOUT_MS = 15000;

  constructor(spec, { spawnFn } = {}) {
    this.spec = spec;
    this.pending = new Map();
    // ChildProcess 'error'(spawn ENOENT 等异步失败)若已触发,记录后让后续 request 立即失败,
    // 不再发请求。配合下面的 process.on('error') 避免 EventEmitter 无监听器 throw 成 uncaught
    // 杀掉整个 gateway 进程(单个坏 MCP 不应波及 gateway,见 plan §10 故障隔离)。
    this.errored = null;
    const config = spec.config ?? {};
    this.requestTimeoutMs = typeof config.request_timeout_ms === 'number'
      ? config.request_timeout_ms
      : null;
    const command = config.command;
    const args = Array.isArray(config.args) ? config.args : [];
    if (!command) {
      throw new Error(`Missing stdio command for ${spec.serverId}`);
    }
    // spawnFn 可注入(测试用 fake 进程,避免真实持久子进程在 Windows 下让 test 进程不退出)
    const doSpawn = spawnFn ?? spawn;
    this.process = doSpawn(command, args, {
      cwd: config.cwd || undefined,
      env: { ...process.env, ...(config.env ?? {}) },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.reader = new FramedReader(this.process.stdout);
    // 真实 MCP server 探测到的帧格式同步到其 stdin,后续写给它的请求帧自适应跟随
    // (多数 server=ndjson/MCP spec 标准;首个 initialize 在探测前发出,默认 ndjson)。
    // 与 gateway-stdio-client.js 对称,见 framing.js 文档。
    this.reader.on('message', (message) => {
      this.process.stdin.__mcpFrameFormat = this.reader.lastFormat || 'ndjson';
      this.onMessage(message);
    });
    this.reader.on('error', (error) => this.rejectAll(error));
    // spawn ENOENT 等异步失败会触发 ChildProcess 的 'error'(不是 'exit')。必须监听,
    // 否则 EventEmitter 无 'error' 监听器时默认 throw → uncaught exception → 整个 gateway 进程崩溃。
    this.process.on('error', (error) => {
      this.errored = error;
      this.rejectAll(error);
    });
    this.process.on('exit', () => this.rejectAll(new Error(`MCP process exited: ${spec.serverId}`)));
  }

  async initialize() {
    await this.request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'melon-mcp-gateway', version: '1.0.0' },
    });
    writeMessage(this.process.stdin, { jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
  }

  async listTools() {
    const result = await this.request('tools/list', {});
    return result?.tools ?? [];
  }

  async callTool(name, args) {
    return this.request('tools/call', { name, arguments: args ?? {} });
  }

  async request(method, params, timeoutMs) {
    // spawn 已失败(ENOENT 等)则立即拒绝,不再往一个没启动的进程 stdin 写请求、干等到超时。
    if (this.errored) {
      throw this.errored;
    }
    const id = nextId++;
    const timeout = timeoutMs ?? this.requestTimeoutMs ?? StdioMcpClient.DEFAULT_REQUEST_TIMEOUT_MS;
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`MCP request timeout: ${method} (${this.spec.serverId}) after ${timeout}ms`));
        }
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
    });
    writeMessage(this.process.stdin, { jsonrpc: '2.0', id, method, params });
    return promise;
  }

  onMessage(message) {
    if (!message || message.id === undefined) return;
    const pending = this.pending.get(message.id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(message.id);
    if (message.error) {
      pending.reject(new Error(message.error.message ?? 'MCP error'));
    } else {
      pending.resolve(message.result);
    }
  }

  close() {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
    }
    try {
      this.process.stdin.end();
    } catch {}
    try {
      this.process.kill();
    } catch {}
  }

  rejectAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
