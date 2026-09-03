// @ts-check
import { spawn } from 'node:child_process';
import { FramedReader, writeMessage } from '../framing.js';
import { killChildTree } from '../../utils/kill-tree.js';

/**
 * @typedef {{ command?: string; args?: string[]; cwd?: string; env?: Record<string, string>; request_timeout_ms?: number }} StdioMcpConfig
 */

/**
 * @typedef {{ serverId: string; transport?: 'stdio'; config?: StdioMcpConfig }} StdioMcpSpec
 */

/**
 * 待决 JSON-RPC 请求记录。
 * @typedef {{ resolve: (value: unknown) => void; reject: (reason: unknown) => void; timer: NodeJS.Timeout }} PendingRequest
 */

/**
 * 注入式 spawn 函数(测试用 fake 进程)。
 * @typedef {(cmd: string, args: string[], options: import('node:child_process').SpawnOptions) => import('node:child_process').ChildProcess} SpawnFn
 */

let nextId = 1;

export class StdioMcpClient {
  // 单次 JSON-RPC 请求默认超时(ms)。单个 MCP server 挂起不应拖垮整个 gateway catalog
  // refresh(否则放大首次延迟)。config.request_timeout_ms 或 request 第三参可覆盖。
  static DEFAULT_REQUEST_TIMEOUT_MS = 15000;
  // tools/call 专用默认超时(ms):工具调用(慢 DB 查询/网页抓取)天然比 initialize/listTools 慢,
  // 15s 会在外腿(gateway TOOLS_CALL_TIMEOUT_MS=60s)远未到时先误杀慢工具。与外腿对齐并留 5s
  // 转发余量;用户显式配置的 config.request_timeout_ms 仍最优先。
  static CALL_TOOL_TIMEOUT_MS = 55000;

  /** @type {StdioMcpSpec} */ spec;
  /** @type {Map<number, PendingRequest>} */ pending;
  /** @type {Error | null} */ errored;
  /** @type {number | null} */ requestTimeoutMs;
  /** @type {import('node:child_process').ChildProcess} */ process;
  /** @type {FramedReader} */ reader;
  /** @type {Promise<void> | null} */ closePromise;
  /** server 推送 notifications/tools/list_changed 时的回调(由 ServerSupervisor 注入,标脏工具缓存)。 @type {(() => void) | null} */
  onToolsListChanged;

  /**
   * @param {StdioMcpSpec} spec server 规格
   * @param {{ spawnFn?: SpawnFn }} [opts] 可选注入 spawn(测试用)
   */
  constructor(spec, { spawnFn } = {}) {
    this.spec = spec;
    this.pending = new Map();
    this.closePromise = null;
    // ChildProcess 'error'(spawn ENOENT 等异步失败)若已触发,记录后让后续 request 立即失败,
    // 不再发请求。配合下面的 process.on('error') 避免 EventEmitter 无监听器 throw 成 uncaught
    // 杀掉整个 gateway 进程(单个坏 MCP 不应波及 gateway,见 plan §10 故障隔离)。
    this.errored = null;
    this.onToolsListChanged = null;
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
    // stdio 配为 pipe,stdout/stdin 必为流;断言非空以解构 Writable/Readable。
    const stdout = /** @type {import('node:stream').Readable} */ (this.process.stdout);
    const stdin = /** @type {import('node:stream').Writable & { __mcpFrameFormat?: string }} */ (this.process.stdin);
    this.reader = new FramedReader(stdout);
    // 真实 MCP server 探测到的帧格式同步到其 stdin,后续写给它的请求帧自适应跟随
    // (多数 server=ndjson/MCP spec 标准;首个 initialize 在探测前发出,默认 ndjson)。
    // 机制详见 framing.js 文档。
    this.reader.on('message', (message) => {
      stdin.__mcpFrameFormat = this.reader.lastFormat || 'ndjson';
      this.onMessage(message);
    });
    this.reader.on('error', (/** @type {Error} */ error) => this.rejectAll(error));
    // spawn ENOENT 等异步失败会触发 ChildProcess 的 'error'(不是 'exit')。必须监听,
    // 否则 EventEmitter 无 'error' 监听器时默认 throw → uncaught exception → 整个 gateway 进程崩溃。
    this.process.on('error', (/** @type {Error} */ error) => this.markDead(error));
    // 进程退出(error/自然退出)后 stdin 写端随之关闭:继续往死 stdin 写会触发 EPIPE。Writable 流无
    // 'error' 监听器时 Node 默认 throw → uncaughtException → 整个 gateway 崩(STAB-01)。stdin 写端
    // 关闭与进程死亡同语义,故与 process 'error'/'exit' 一样走 markDead(置 errored + rejectAll)。
    stdin.on('error', (/** @type {Error} */ error) => this.markDead(error));
    // 进程退出须置 errored(STAB-02):否则 supervisor 持有的死 client 仍非 null,后续 catalog refresh
    // 复用死 client 调 listTools 写已关闭 stdin,等满 DEFAULT_REQUEST_TIMEOUT_MS=15s 才超时;坏 MCP 反复
    // 触发持续拖慢首屏。置 errored 后 request() 立即抛、supervisor 检测死 client 即重建重连。
    this.process.on('exit', () => this.markDead(new Error(`MCP process exited: ${spec.serverId}`)));
  }

  async initialize() {
    await this.request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'melon-mcp-gateway', version: '1.0.0' },
    });
    const stdin = /** @type {import('node:stream').Writable} */ (this.process.stdin);
    writeMessage(stdin, { jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
  }

  async listTools() {
    const result = /** @type {{ tools?: unknown[] }} */ (await this.request('tools/list', {}));
    return result?.tools ?? [];
  }

  /**
   * @param {string} name
   * @param {Record<string, unknown> | null | undefined} args
   * @param {AbortSignal} [signal] 取消信号(abort 时向 server 发 notifications/cancelled)
   */
  async callTool(name, args, signal) {
    return this.request('tools/call', { name, arguments: args ?? {} },
      this.requestTimeoutMs ?? StdioMcpClient.CALL_TOOL_TIMEOUT_MS, signal);
  }

  /**
   * @param {string} method JSON-RPC method
   * @param {Record<string, unknown>} params JSON-RPC params
   * @param {number} [timeoutMs] 可选单次超时覆盖
   * @param {AbortSignal} [signal] 取消信号(gateway 侧客户端断开透传)
   * @returns {Promise<unknown>} JSON-RPC result 字段
   */
  async request(method, params, timeoutMs, signal) {
    // spawn 已失败(ENOENT 等)则立即拒绝,不再往一个没启动的进程 stdin 写请求、干等到超时。
    if (signal?.aborted) {
      throw new Error(`MCP request cancelled before send: ${method} (${this.spec.serverId})`);
    }
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
    const stdin = /** @type {import('node:stream').Writable} */ (this.process.stdin);
    // 取消传播(总则六):abort 时 reject pending 并向 server 发 notifications/cancelled(MCP spec),
    // 让真实 server 侧的慢工具确定性停止,而非在 gateway 里空跑满 CALL_TOOL_TIMEOUT_MS。
    const onAbort = () => {
      const entry = this.pending.get(id);
      if (!entry) return;
      clearTimeout(entry.timer);
      this.pending.delete(id);
      try {
        writeMessage(stdin, {
          jsonrpc: '2.0',
          method: 'notifications/cancelled',
          params: { requestId: id, reason: 'gateway client disconnected' },
        });
      } catch {}
      entry.reject(new Error(`MCP request cancelled: ${method} (${this.spec.serverId})`));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
    try {
      writeMessage(stdin, { jsonrpc: '2.0', id, method, params });
      return await promise;
    } finally {
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** @param {any} message 收到的 JSON-RPC 消息(FramedReader 解析产物) */
  onMessage(message) {
    if (!message) return;
    if (message.id === undefined) {
      // 无 id = notification;tools/list_changed 表示 server 工具集已变,通知 supervisor 标脏缓存
      if (message.method === 'notifications/tools/list_changed') {
        try {
          this.onToolsListChanged?.();
        } catch {}
      }
      return;
    }
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
    if (this.closePromise) return this.closePromise;
    const error = new Error(`MCP client closed: ${this.spec.serverId}`);
    this.rejectAll(error);
    this.closePromise = new Promise((resolve) => {
      if (this.process.exitCode != null || this.process.signalCode != null) {
        resolve();
        return;
      }
      let settled = false;
      /** @type {NodeJS.Timeout} */
      let retryTimer;
      /** @type {NodeJS.Timeout} */
      let deadlineTimer;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(retryTimer);
        clearTimeout(deadlineTimer);
        resolve();
      };
      retryTimer = setTimeout(() => {
        try {
          killChildTree(this.process, this.spec.serverId);
        } catch {}
      }, 500);
      retryTimer.unref?.();
      deadlineTimer = setTimeout(() => {
        try {
          killChildTree(this.process, this.spec.serverId);
        } catch {}
        finish();
      }, 2_000);
      deadlineTimer.unref?.();
      this.process.once('exit', finish);
      this.process.once('close', finish);
      this.process.once('error', finish);
      try {
        const stdin = /** @type {import('node:stream').Writable} */ (this.process.stdin);
        stdin.end();
      } catch {}
      try {
        // Windows 下 MCP server 常经 cmd.exe 包装启动:杀整树,而非仅杀包装壳。
        killChildTree(this.process, this.spec.serverId);
      } catch {}
    });
    return this.closePromise;
  }

  /**
   * 标记 client 已死(进程 'error'/'exit' 或 stdin EPIPE):置 errored 使后续 request() 立即失败,
   * 并 rejectAll 消化所有 pending。幂等守卫避免覆盖首个根因 error。
   * @param {Error} error
   */
  markDead(error) {
    if (!this.errored) this.errored = error;
    this.rejectAll(error);
  }

  /** @param {Error} error */
  rejectAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
