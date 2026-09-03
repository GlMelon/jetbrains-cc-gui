// @ts-check
import http from 'node:http';
import { requireToken } from './security.js';
import { readJson } from './http-body.js';
import { McpStreamableHttpEndpoint } from './streamable-http.js';
import { ServerSupervisor } from './server-supervisor.js';
import { buildCatalog } from './tool-catalog.js';
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
    // MCP 数据面端点(Streamable HTTP):CLI 直连 /mcp,latestRevision 由本类簿记,getter 透传。
    /** @type {McpStreamableHttpEndpoint} */
    this.mcpEndpoint = new McpStreamableHttpEndpoint({
      revisionStore,
      // ToolRouter 构造期望 Map<string, SupervisorLike>(仅 callTool);supervisors 含 stop/refresh 等
      // 额外成员,结构上满足 SupervisorLike,强转安全。
      supervisors: /** @type {any} */ (supervisors),
      getLatestRevision: () => this.latestRevision,
    });
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
    // 先结束 MCP 端点的全部 SSE 长连接(干净 EOF),否则长连接会卡住 server.close
    // 直到 shutdownDeadlineMs 到期被强毁,CLI 侧表现为无征兆断连。
    this.mcpEndpoint.close();
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
      if (req.method === 'POST' && req.url === '/stop') {
        this.write(res, 200, { ok: true });
        setTimeout(() => {
          void this.close().then(() => process.exit(0), () => process.exit(1));
        }, 20);
        return;
      }
      // MCP 数据面(Streamable HTTP):CLI 直连;GET=SSE 下行流 / POST=JSON-RPC / DELETE=终止 session。
      if (req.url === '/mcp' || req.url?.startsWith('/mcp?')) {
        await this.mcpEndpoint.handle(req, res);
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
    /** @type {Set<string>} 本次新建/替换的 supervisor key:必须 refresh 建立初始工具列表 */
    const created = new Set();
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
        created.add(key);
      }
    }
    for (const key of [...this.supervisors.keys()]) {
      if (!desired.has(key)) {
        this.supervisors.get(key)?.stop();
        this.supervisors.delete(key);
        this.healthStore.remove(key);
      }
    }
    // 选择性刷新(性能修复):此前每次 snapshot 对全部 supervisor 无差别 refresh()——即使配置
    // 未变且健康,也会对每个 server 重跑 listTools(http 型 server 是真实网络往返),全部阻塞
    // POST /snapshot 响应,拉长 Java 侧 postMs、挤压 buildCliConfig 的 2s 发送预算。现在只刷:
    //   ① 本次新建/替换(configHash 变化)的 supervisor——必须建立初始工具列表;
    //   ② 不健康的(上次 refresh 失败/BACKOFF 退避到期重试,refresh 内部仍有冷却短路);
    //   ③ client 已死的(stdio 进程退出,须重建重连);
    //   ④ server 推送过 notifications/tools/list_changed 的(toolsStale,工具集已变)。
    // 健康且未变的直接用缓存 tools,零 IPC/网络开销。
    /** @type {Promise<unknown>[]} */
    const refreshes = [];
    for (const [key, supervisor] of this.supervisors) {
      if (!created.has(key) && supervisor.healthy && !supervisor.toolsStale
          && !(typeof supervisor.isClientDead === 'function' && supervisor.isClientDead())) {
        continue;
      }
      refreshes.push(supervisor.refresh());
    }
    await Promise.allSettled(refreshes);
    if (this.closing) throw new Error('gateway shutting down');
    this.revisionStore.put(revision, buildCatalog(revision, this.supervisors));
    // catalog 已变更:向全部 /mcp GET SSE 流广播 list_changed,CLI 侧即时重列工具
    // (不开流的 client 下次 tools/list 自然拿到最新 catalog,无状态残留)。
    this.mcpEndpoint.notifyToolsListChanged();
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

