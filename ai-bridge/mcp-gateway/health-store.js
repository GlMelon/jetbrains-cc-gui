// @ts-check
/**
 * Gateway MCP server 健康状态存储:按 `${sourceProvider}:${serverId}` 维护最新一条健康记录,
 * 供 IpcServer /status 与 snapshot 聚合返回。
 */

/**
 * 健康记录条目(结构对齐 server-supervisor.js 写入的 HealthEntry)。
 * @typedef {{ serverId: string; sourceProvider: string; state: string; lastError: string | null; lastSuccessAt: number | null; failureCount: number } & Record<string, unknown>} HealthEntry
 */

export class HealthStore {
  constructor() {
    /** @type {Map<string, HealthEntry>} */
    this.servers = new Map();
  }

  /**
   * @param {string} key `${sourceProvider}:${serverId}`
   * @param {HealthEntry} value 健康记录(浅拷贝隔离)
   * @returns {void}
   */
  set(key, value) {
    this.servers.set(key, { ...value });
  }

  /**
   * @param {string} key
   * @returns {void}
   */
  remove(key) {
    this.servers.delete(key);
  }

  /**
   * @param {number} revision 当前版本号
   * @param {number} uptimeMs 进程已运行毫秒
   * @returns {{ revision: number; uptimeMs: number; servers: HealthEntry[] }}
   */
  snapshot(revision, uptimeMs) {
    return {
      revision,
      uptimeMs,
      servers: [...this.servers.values()],
    };
  }
}
