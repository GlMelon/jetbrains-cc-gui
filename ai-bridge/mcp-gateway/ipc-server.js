// @ts-check
import http from 'node:http';
import { requireToken } from './security.js';
import { ServerSupervisor } from './server-supervisor.js';
import { buildCatalog } from './tool-catalog.js';
import { ToolRouter } from './tool-router.js';
import { RevisionStore } from './revision-store.js';
import { HealthStore } from './health-store.js';

/**
 * supervisors 的值类型:ServerSupervisor + 运行时注入的 configHash(由本模块写入,基类未声明)。
 * @typedef {ServerSupervisor & { configHash?: string }} SupervisorEntry
 */

export class IpcServer {
  /**
   * @param {{ token: string; revisionStore: RevisionStore; healthStore: HealthStore; supervisors: Map<string, SupervisorEntry>; startedAt: number; shutdownDeadlineMs?: number }} opts
   */
  constructor({ token, revisionStore, healthStore, supervisors, startedAt, shutdownDeadlineMs = 5_000 }) {
    /** @type {string} */
    this.token = token;
    /** @type {RevisionStore} */
    this.revisionStore = revisionStore;
    /** @type {HealthStore} */
    this.healthStore = healthStore;
    /** @type {Map<string, SupervisorEntry>} */
    this.supervisors = supervisors;
    /** @type {number} */
    this.startedAt = startedAt;
    /** @type {number} */
    this.latestRevision = 0;
    /** @type {number} */
    this.shutdownDeadlineMs = shutdownDeadlineMs;
    /** @type {boolean} */
    this.closing = false;
    /** @type {Promise<void> | null} */
    this.shutdownPromise = null;
    /** @type {Set<import('node:net').Socket>} */
    this.connections = new Set();
    /** @type {http.Server} */
    this.server = http.createServer((req, res) => this.handle(req, res));
    this.server.on('connection', (socket) => {
      this.connections.add(socket);
      socket.on('close', () => this.connections.delete(socket));
    });
  }

  /**
   * @returns {Promise<number>} 实际监听端口
   */
  listen() {
    return new Promise((resolve) => {
      this.server.listen(0, '127.0.0.1', () => {
        const addr = /** @type {import('node:net').AddressInfo} */ (this.server.address());
        resolve(addr.port);
      });
    });
  }

  /**
   * Gracefully close supervisors and HTTP connections. Repeated calls share one
   * shutdown promise; the deadline is the final guard before force-closing sockets.
   *
   * @returns {Promise<void>}
   */
  close() {
    if (this.shutdownPromise) return this.shutdownPromise;
    this.closing = true;
    const stopPromise = Promise.allSettled(
      [...this.supervisors.values()].map((supervisor) => Promise.resolve().then(() => supervisor.stop())),
    );
    /** @type {Promise<void>} */
    const serverPromise = new Promise((resolve) => {
      if (!this.server.listening) {
        resolve();
        return;
      }
      this.server.close(() => resolve());
    });
    /** @type {NodeJS.Timeout | undefined} */
    let deadlineTimer;
    /** @type {Promise<void>} */
    const deadline = new Promise((resolve) => {
      deadlineTimer = setTimeout(resolve, this.shutdownDeadlineMs);
      deadlineTimer.unref?.();
    });
    this.shutdownPromise = Promise.race([Promise.all([stopPromise, serverPromise]), deadline])
      .then(() => {
        if (deadlineTimer) clearTimeout(deadlineTimer);
        if (this.connections.size > 0) {
          for (const socket of this.connections) socket.destroy();
          this.connections.clear();
        }
      })
      .then(() => undefined);
    return this.shutdownPromise;
  }

  /**
   * @param {http.IncomingMessage} req
   * @param {http.ServerResponse} res
   * @returns {Promise<void>}
   */
  async handle(req, res) {
    if (!requireToken(req, this.token)) {
      this.write(res, 401, { error: 'unauthorized' });
      return;
    }
    if (this.closing) {
      this.write(res, 503, { error: 'gateway shutting down' });
      return;
    }
    try {
      if (req.method === 'POST' && req.url === '/snapshot') {
        const body = await readJson(req);
        await this.applySnapshot(body);
        this.write(res, 200, this.status());
        return;
      }
      if (req.method === 'GET' && req.url === '/status') {
        this.write(res, 200, this.status());
        return;
      }
      if (req.method === 'GET' && req.url?.startsWith('/runtime/tools/list')) {
        const url = new URL(req.url, 'http://127.0.0.1');
        const revision = Number(url.searchParams.get('revision') || this.latestRevision);
        const catalog = this.revisionStore.get(revision);
        this.write(res, 200, { tools: catalog.tools ?? [] });
        return;
      }
      if (req.method === 'POST' && req.url === '/runtime/tools/call') {
        const body = await readJson(req);
        // ToolRouter 构造期望 Map<string, SupervisorLike>(仅 callTool);supervisors 含 stop/refresh/configHash,
        // 受 Map 不变性限制无法直接赋值,结构上含 callTool,强转安全。
        const router = new ToolRouter(/** @type {any} */ (this.supervisors));
        // 取消传播(总则六确定性取消):CLI 会话被 interrupt → stdio 桥进程死/stdin 关 → 本 socket
        // 断开。res 'close' 且 writableEnded=false 即客户端中途断开;abort 经 ToolRouter→supervisor
        // 透传到真实 server(stdio 侧发 notifications/cancelled),慢工具不再在 gateway 里空跑满
        // 内腿 CALL_TOOL_TIMEOUT_MS(55s)。
        const controller = new AbortController();
        const onPrematureClose = () => {
          if (!res.writableEnded) controller.abort();
        };
        res.on('close', onPrematureClose);
        /** @type {unknown} */
        let result;
        try {
          result = await router.call(body.name, body.arguments ?? {}, body.revision, controller.signal);
        } finally {
          res.removeListener('close', onPrematureClose);
        }
        this.write(res, 200, result);
        return;
      }
      if (req.method === 'POST' && req.url === '/stop') {
        this.write(res, 200, { ok: true });
        setTimeout(() => {
          void this.close().then(() => process.exit(0), () => process.exit(1));
        }, 20);
        return;
      }
      this.write(res, 404, { error: 'not found' });
    } catch (error) {
      // 客户端中途断开(取消传播路径)时 socket 已销毁,回写 500 会抛 write-after-end;
      // 仅在连接仍存活时回写错误响应。
      if (!res.destroyed && !res.writableEnded) {
        this.write(res, 500, { error: error instanceof Error ? error.message : String(error) });
      }
    }
  }

  /**
   * @param {Record<string, any>} snapshot
   * @returns {Promise<void>}
   */
  async applySnapshot(snapshot) {
    if (this.closing) throw new Error('gateway shutting down');
    const revision = Number(snapshot.revision || 0);
    this.latestRevision = Math.max(this.latestRevision, revision);
    /** @type {Map<string, ServerSpecLike>} */
    const desired = new Map();
    for (const spec of snapshot.servers ?? []) {
      if (!spec.enabled) continue;
      const key = `${spec.sourceProvider}:${spec.serverId}`;
      desired.set(key, spec);
      const existing = this.supervisors.get(key);
      const nextHash = JSON.stringify(spec);
      if (!existing || existing.configHash !== nextHash) {
        existing?.stop();
        /** @type {SupervisorEntry} */
        const supervisor = new ServerSupervisor(spec, this.healthStore);
        supervisor.configHash = nextHash;
        this.supervisors.set(key, supervisor);
      }
    }
    for (const key of [...this.supervisors.keys()]) {
      if (!desired.has(key)) {
        this.supervisors.get(key)?.stop();
        this.supervisors.delete(key);
        this.healthStore.remove(key);
      }
    }
    const refreshes = [...this.supervisors.values()].map((supervisor) => supervisor.refresh());
    await Promise.allSettled(refreshes);
    if (this.closing) throw new Error('gateway shutting down');
    this.revisionStore.put(revision, buildCatalog(revision, this.supervisors));
  }

  /**
   * @returns {{ revision: number; uptimeMs: number; servers: unknown[] }}
   */
  status() {
    return this.healthStore.snapshot(this.latestRevision, Date.now() - this.startedAt);
  }

  /**
   * @param {http.ServerResponse} res
   * @param {number} status
   * @param {unknown} body
   * @returns {void}
   */
  write(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  }
}

/**
 * snapshot.servers 每项的最小结构。
 * @typedef {{ enabled?: unknown; sourceProvider: string; serverId: string } & Record<string, unknown>} ServerSpecLike
 */

/**
 * @param {http.IncomingMessage} req
 * @returns {Promise<any>}
 */
// 项12:HTTP 请求体字节上限,防超大请求体撑爆内存(与 FramedReader MAX_MESSAGE_BYTES 对称)。
const MAX_REQUEST_BYTES = 16 * 1024 * 1024;
function readJson(/** @type {http.IncomingMessage} */ req) {
  return new Promise((resolve, reject) => {
    /** @type {Buffer[]} */
    const chunks = [];
    let total = 0;
    let aborted = false;
    req.on('data', (/** @type {Buffer} */ chunk) => {
      if (aborted) return;
      total += chunk.length;
      if (total > MAX_REQUEST_BYTES) {
        aborted = true;
        reject(new Error(`Request body exceeds ${MAX_REQUEST_BYTES} bytes`));
        try { req.destroy(); } catch { /* best effort */ }
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (aborted) return;
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}
