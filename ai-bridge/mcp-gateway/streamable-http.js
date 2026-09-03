// @ts-check
/**
 * MCP Streamable HTTP 端点(2026-09 数据面改造):取代「每 CLI 会话一个 stdio 代理进程」,
 * CLI(Claude/Codex/OpenCode)以 url 方式直连 Gateway 的 `/mcp` 单端点。
 *
 * 设计要点:
 * - 单端点三动词:POST=JSON-RPC 请求/通知,GET=SSE 下行流(server → client 通知),
 *   DELETE=显式终止 session。POST 响应固定 `application/json`(spec 允许的两种之一),
 *   不实现上行 SSE 响应模式与 `Last-Event-ID` 事件重放(均为 spec 可选)。
 * - session 语义:`initialize` 时签发 `Mcp-Session-Id`;此后请求须携带,缺失 400、未知 404
 *   (client 按 spec 重新 initialize)。registry 有容量上限 + LRU 逐出 + 空闲 TTL sweeper,
 *   防多 CLI 长会话场景 map 无界增长。
 * - tools/list 永远返回**最新** catalog(getLatestRevision),取代 stdio 时代「spawn 钉死
 *   revision + RevisionStore 淘汰回退 + stale 标记」整套兜底;catalog 变更经 GET SSE 流
 *   广播 `notifications/tools/list_changed`,不开流的 client 退化为下次 list 才感知,
 *   不劣于 stdio 旧语义(整会话钉死)。
 * - 取消传播(总则六):tools/call 挂 `res close → AbortController.abort`,CLI 中断会话
 *   断开 socket 即确定性取消,慢工具不在 gateway 空跑。
 * - 关闭语义:close() 先对所有 SSE 流 res.end()(给 CLI 干净 EOF),再由 ipc-server 走
 *   server.close + deadline 强毁,长连接不再卡住 graceful shutdown。
 * - 鉴权由 ipc-server 全局 requireToken 统一承担(Bearer,仅 loopback);本模块不重复校验,
 *   也不做 Origin 校验(非浏览器客户端,loopback + bearer 已足够)。
 */
import crypto from 'node:crypto';
import { readJson } from './http-body.js';
import { ToolRouter } from './tool-router.js';

/** 本端点声明支持的 MCP 协议版本;client 请求命中则回声,否则回最新由 client 决策。 */
const SUPPORTED_PROTOCOL_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18'];
const LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[SUPPORTED_PROTOCOL_VERSIONS.length - 1];

/** session registry 容量上限:超出时按 LRU 逐出最旧 session(并结束其 SSE 流)。 */
const MAX_SESSIONS = 64;
/** 空闲 session 存活时长:多 tab CLI 偶发调工具,给足余量;到期 404 由 client 重 initialize。 */
const SESSION_IDLE_TTL_MS = 2 * 60 * 60 * 1000;
/** 空闲 sweeper 周期(unref,不阻止进程退出)。 */
const SWEEP_INTERVAL_MS = 10 * 60 * 1000;
/** SSE 心跳注释行周期:防中间层把长连接当死连接断开。 */
const HEARTBEAT_MS = 25 * 1000;

/** JSON-RPC 错误码。 */
const PARSE_ERROR = -32700;
const INVALID_REQUEST = -32600;
const METHOD_NOT_FOUND = -32601;
const INTERNAL_ERROR = -32000;

/**
 * @typedef {{
 *   id: string;
 *   protocolVersion: string;
 *   createdAt: number;
 *   lastSeenAt: number;
 *   streams: Set<import('node:http').ServerResponse>;
 * }} McpSession
 */

/**
 * @typedef {{ jsonrpc?: string; id?: unknown; method?: string; params?: Record<string, unknown> } & Record<string, unknown>} JsonRpcMessage
 */

export class McpStreamableHttpEndpoint {
  /**
   * @param {{
   *   revisionStore: import('./revision-store.js').RevisionStore;
   *   supervisors: Map<string, import('./tool-router.js').SupervisorLike>;
   *   getLatestRevision: () => number;
   * }} opts
   */
  constructor({ revisionStore, supervisors, getLatestRevision }) {
    /** @type {import('./revision-store.js').RevisionStore} */
    this.revisionStore = revisionStore;
    /** @type {Map<string, import('./tool-router.js').SupervisorLike>} */
    this.supervisors = supervisors;
    /** @type {() => number} */
    this.getLatestRevision = getLatestRevision;
    /** @type {Map<string, McpSession>} 插入序即 LRU 序(访问时重插到尾部)。 */
    this.sessions = new Map();
    /** @type {boolean} */
    this.closed = false;
    // 单一心跳 timer 写全部流,避免每流一个 timer 的资源开销;unref 不阻止进程退出。
    /** @type {NodeJS.Timeout} */
    this.heartbeatTimer = setInterval(() => this.writeHeartbeat(), HEARTBEAT_MS);
    this.heartbeatTimer.unref?.();
    /** @type {NodeJS.Timeout} */
    this.sweeperTimer = setInterval(() => this.sweepIdleSessions(), SWEEP_INTERVAL_MS);
    this.sweeperTimer.unref?.();
  }

  /**
   * 端点入口(ipc-server 路由 `/mcp` 后委派);按 HTTP 动词分派。
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @returns {Promise<void>}
   */
  async handle(req, res) {
    if (this.closed) {
      this.writeJson(res, 503, { error: 'gateway shutting down' });
      return;
    }
    if (req.method === 'POST') {
      await this.handlePost(req, res);
      return;
    }
    if (req.method === 'GET') {
      this.handleGet(req, res);
      return;
    }
    if (req.method === 'DELETE') {
      this.handleDelete(req, res);
      return;
    }
    this.writeJson(res, 405, { error: 'method not allowed' });
  }

  /**
   * POST:单条或 batch JSON-RPC。纯 notification/response → 202 空 body;
   * 含请求 → JSON 响应(batch 输入回 batch 数组)。
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @returns {Promise<void>}
   */
  async handlePost(req, res) {
    /** @type {unknown} */
    let body;
    try {
      body = await readJson(req);
    } catch {
      this.writeJson(res, 400, this.errorMessage(null, PARSE_ERROR, 'Parse error'));
      return;
    }
    const batch = Array.isArray(body);
    /** @type {unknown[]} */
    const messages = batch ? /** @type {unknown[]} */ (body) : [body];
    if (messages.length === 0 || messages.some((m) => !m || typeof m !== 'object' || Array.isArray(m))) {
      this.writeJson(res, 400, this.errorMessage(null, INVALID_REQUEST, 'Invalid Request'));
      return;
    }
    /** @type {JsonRpcMessage[]} */
    const rpcMessages = /** @type {JsonRpcMessage[]} */ (messages);

    // initialize 永远单独成请求(client 不会把 initialize 塞进 batch),防御性地只取第一条判定。
    const isInitialize = rpcMessages.some((m) => m.method === 'initialize');
    /** @type {McpSession | null} */
    let session;
    if (isInitialize) {
      const initMessage = /** @type {JsonRpcMessage} */ (rpcMessages.find((m) => m.method === 'initialize'));
      session = this.createSession(initMessage.params?.protocolVersion);
    } else {
      session = this.resolveSession(req);
      if (!session) {
        const hasHeader = typeof req.headers['mcp-session-id'] === 'string';
        this.writeJson(res, hasHeader ? 404 : 400,
          this.errorMessage(rpcMessages[0]?.id ?? null, INVALID_REQUEST,
            hasHeader ? 'Unknown or expired session; re-initialize' : 'Missing Mcp-Session-Id header'));
        return;
      }
    }

    /** @type {unknown[]} */
    const responses = [];
    for (const message of rpcMessages) {
      // 无 id 的消息是 notification/response:处理后不回写(202 语义)。
      if (message.id === undefined || message.id === null) {
        continue;
      }
      try {
        const result = await this.dispatch(message, session, res);
        responses.push({ jsonrpc: '2.0', id: message.id, result });
      } catch (error) {
        responses.push(this.errorMessage(message.id,
          error instanceof Error && 'code' in error ? Number(error.code) : INTERNAL_ERROR,
          error instanceof Error ? error.message : String(error)));
      }
    }
    if (responses.length === 0) {
      res.writeHead(202);
      res.end();
      return;
    }
    /** @type {Record<string, string>} */
    const headers = { 'content-type': 'application/json' };
    if (isInitialize && session) {
      headers['mcp-session-id'] = session.id;
    }
    res.writeHead(200, headers);
    res.end(JSON.stringify(batch ? responses : responses[0]));
  }

  /**
   * GET:开 SSE 下行流并登记到 session;连接断开自动注销。
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @returns {void}
   */
  handleGet(req, res) {
    const session = this.resolveSession(req);
    if (!session) {
      const hasHeader = typeof req.headers['mcp-session-id'] === 'string';
      this.writeJson(res, hasHeader ? 404 : 400,
        { error: hasHeader ? 'Unknown or expired session; re-initialize' : 'Missing Mcp-Session-Id header' });
      return;
    }
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    session.streams.add(res);
    req.on('close', () => {
      session.streams.delete(res);
    });
  }

  /**
   * DELETE:显式终止 session 并结束其 SSE 流。
   *
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @returns {void}
   */
  handleDelete(req, res) {
    const session = this.resolveSession(req);
    if (!session) {
      this.writeJson(res, 404, { error: 'Unknown or expired session' });
      return;
    }
    this.destroySession(session.id);
    this.writeJson(res, 200, { ok: true });
  }

  /**
   * 新 revision 发布后由 ipc-server 调用:向全部 SSE 流广播 tools/list_changed。
   *
   * @returns {void}
   */
  notifyToolsListChanged() {
    this.broadcast({ jsonrpc: '2.0', method: 'notifications/tools/list_changed' });
  }

  /**
   * 关闭端点:停 timer、结束全部 SSE 流、清空 session。幂等。
   *
   * @returns {void}
   */
  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.heartbeatTimer);
    clearInterval(this.sweeperTimer);
    for (const session of this.sessions.values()) {
      this.endStreams(session);
    }
    this.sessions.clear();
  }

  // ── 内部分派 ──────────────────────────────────────────────────────────────

  /**
   * 单条 JSON-RPC 请求分派;抛带 code 的 Error 表示 JSON-RPC 错误。
   *
   * @param {JsonRpcMessage} message
   * @param {McpSession} session
   * @param {import('node:http').ServerResponse} res 用于 tools/call 的断连取消传播
   * @returns {Promise<unknown>}
   */
  async dispatch(message, session, res) {
    switch (message.method) {
      case 'initialize':
        // session 已在 handlePost 按协商版本创建;此处只组装协议响应。
        return {
          protocolVersion: session.protocolVersion,
          capabilities: { tools: { listChanged: true } },
          serverInfo: { name: 'melon-mcp-gateway', version: '1.0.0' },
        };
      case 'ping':
        return {};
      case 'tools/list': {
        // 永远返回最新 catalog(替代 stdio 时代钉死 revision + stale 降级)。
        const catalog = this.revisionStore.get(this.getLatestRevision());
        return { tools: catalog.tools ?? [] };
      }
      case 'tools/call': {
        const params = message.params ?? {};
        // 取消传播:CLI 中断 → socket 断开 → res close 且响应未写完 → abort 透传到真实 server。
        const controller = new AbortController();
        const onPrematureClose = () => {
          if (!res.writableEnded) controller.abort();
        };
        res.on('close', onPrematureClose);
        try {
          const router = new ToolRouter(this.supervisors);
          return await router.call(/** @type {string} */ (params.name), params.arguments ?? {},
            this.getLatestRevision(), controller.signal);
        } finally {
          res.removeListener('close', onPrematureClose);
        }
      }
      default:
        throw Object.assign(new Error(`Method not found: ${message.method}`), { code: METHOD_NOT_FOUND });
    }
  }

  /**
   * 创建 session:协商协议版本,登记 registry 并执行 LRU 容量逐出。
   *
   * @param {unknown} requestedVersion client initialize 的 protocolVersion
   * @returns {McpSession}
   */
  createSession(requestedVersion) {
    const protocolVersion = typeof requestedVersion === 'string'
        && SUPPORTED_PROTOCOL_VERSIONS.includes(requestedVersion)
      ? requestedVersion
      : LATEST_PROTOCOL_VERSION;
    /** @type {McpSession} */
    const session = {
      id: crypto.randomUUID(),
      protocolVersion,
      createdAt: Date.now(),
      lastSeenAt: Date.now(),
      streams: new Set(),
    };
    this.sessions.set(session.id, session);
    // 容量逐出:Map 插入序即 LRU 序,头部为最旧;结束其 SSE 流给 client 干净 EOF。
    while (this.sessions.size > MAX_SESSIONS) {
      const oldestKey = this.sessions.keys().next().value;
      if (oldestKey === undefined) break;
      this.destroySession(oldestKey);
    }
    return session;
  }

  /**
   * 按 `Mcp-Session-Id` 头解析 session;命中时刷新 LRU 序与活跃时间。
   *
   * @param {import('node:http').IncomingMessage} req
   * @returns {McpSession | null}
   */
  resolveSession(req) {
    const id = req.headers['mcp-session-id'];
    if (typeof id !== 'string' || !id) return null;
    const session = this.sessions.get(id);
    if (!session) return null;
    session.lastSeenAt = Date.now();
    // 重插到尾部维持 LRU 序。
    this.sessions.delete(id);
    this.sessions.set(id, session);
    return session;
  }

  /**
   * 销毁 session:结束其 SSE 流并从 registry 删除。
   *
   * @param {string} id
   * @returns {void}
   */
  destroySession(id) {
    const session = this.sessions.get(id);
    if (!session) return;
    this.endStreams(session);
    this.sessions.delete(id);
  }

  /**
   * 空闲 TTL 清理:sweeper 周期触发,逐出 lastSeenAt 超龄的 session。
   *
   * @returns {void}
   */
  sweepIdleSessions() {
    const deadline = Date.now() - SESSION_IDLE_TTL_MS;
    for (const session of [...this.sessions.values()]) {
      if (session.lastSeenAt < deadline) {
        this.destroySession(session.id);
      }
    }
  }

  /**
   * 向全部 session 的全部 SSE 流广播一条 JSON-RPC 通知。
   *
   * @param {Record<string, unknown>} notification
   * @returns {void}
   */
  broadcast(notification) {
    const frame = `event: message\ndata: ${JSON.stringify(notification)}\n\n`;
    for (const session of this.sessions.values()) {
      for (const stream of session.streams) {
        this.safeWrite(stream, frame);
      }
    }
  }

  /**
   * SSE 心跳:注释行(以冒号开头),不含业务语义。
   *
   * @returns {void}
   */
  writeHeartbeat() {
    for (const session of this.sessions.values()) {
      for (const stream of session.streams) {
        this.safeWrite(stream, ':hb\n\n');
      }
    }
  }

  /**
   * 写 SSE 帧前的防御:已销毁/已结束的流跳过并摘除,防 write-after-end 抛异常。
   *
   * @param {import('node:http').ServerResponse} stream
   * @param {string} frame
   * @returns {void}
   */
  safeWrite(stream, frame) {
    if (stream.destroyed || stream.writableEnded) return;
    try {
      stream.write(frame);
    } catch { /* best effort:连接竞态中断时由 close 事件摘除 */ }
  }

  /**
   * 结束一个 session 的全部 SSE 流(干净 EOF)。
   *
   * @param {McpSession} session
   * @returns {void}
   */
  endStreams(session) {
    for (const stream of session.streams) {
      if (!stream.destroyed && !stream.writableEnded) {
        try { stream.end(); } catch { /* best effort */ }
      }
    }
    session.streams.clear();
  }

  /**
   * @param {unknown} id
   * @param {number} code
   * @param {string} message
   * @returns {{ jsonrpc: string; id: unknown; error: { code: number; message: string } }}
   */
  errorMessage(id, code, message) {
    return { jsonrpc: '2.0', id, error: { code, message } };
  }

  /**
   * @param {import('node:http').ServerResponse} res
   * @param {number} status
   * @param {unknown} body
   * @returns {void}
   */
  writeJson(res, status, body) {
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  }
}
