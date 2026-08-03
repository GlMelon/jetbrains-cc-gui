// @ts-check
/**
 * 单个 MCP server 的监督者:负责建连/刷新工具列表/转发调用/维护健康状态。
 */
import { createMcpClient } from './transport/client-factory.js';
import { gatewayToolName } from './tool-router.js';

/**
 * MCP server 配置项:以 createMcpClient 的入参类型(即 McpServerSpec)为基础,
 * 追加 sourceProvider(路由维度)。用 Parameters<...> 引用,避免跨模块 typedef 重复定义。
 * @typedef {Parameters<typeof createMcpClient>[0] & { sourceProvider: string }} ServerSpec
 */

/**
 * 原始工具(MCP server tools/list 返回项;inputSchema 蛇形/驼峰两种命名兼容)。
 * @typedef {{ name: string; description?: string; inputSchema?: Record<string, unknown>; input_schema?: Record<string, unknown> }} RawTool
 */

/**
 * 汇总到 gateway catalog 的工具条目。
 * @typedef {{ name: string; description?: string; inputSchema: Record<string, unknown> }} GatewayTool
 */

/**
 * 健康记录条目。
 * @typedef {{
 *   serverId: string;
 *   sourceProvider: string;
 *   state: string;
 *   lastError: string | null;
 *   lastSuccessAt: number | null;
 *   failureCount: number;
 * }} HealthEntry
 */

/**
 * HealthStore 最小接口(只用到 set)。
 * @typedef {{ set: (key: string, value: HealthEntry) => void }} HealthStoreLike
 */

export class ServerSupervisor {
  /**
   * @param {ServerSpec} spec
   * @param {HealthStoreLike} healthStore
   */
  constructor(spec, healthStore) {
    /** @type {ServerSpec} */
    this.spec = spec;
    /** @type {HealthStoreLike} */
    this.healthStore = healthStore;
    /** @type {string} */
    this.key = `${spec.sourceProvider}:${spec.serverId}`;
    /** @type {ReturnType<typeof createMcpClient> | null} */
    this.client = null;
    /** @type {GatewayTool[]} */
    this.tools = [];
    /** @type {Map<string, string>} */
    this.routeNames = new Map();
    /** @type {number} */
    this.failureCount = 0;
    /** @type {boolean} */
    this.refreshing = false;
    this.setHealth('STARTING');
  }

  /**
   * 当前 client 是否已死(仅 stdio:进程 exit/error 后由 StdioMcpClient 置 errored;http client
   * 无持久进程,每次 request 独立 fetch,恒活)。用 'errored' in 守卫,避免触碰 HttpMcpClient
   * 不存在的字段。
   * @returns {boolean}
   */
  isClientDead() {
    const client = this.client;
    return !!client && 'errored' in client && !!client.errored;
  }

  /**
   * 重建/刷新工具列表;并发调用合并为一次(由 refreshing 标志串行化)。
   *
   * @returns {Promise<GatewayTool[]>}
   */
  async refresh() {
    if (this.refreshing) return this.tools;
    this.refreshing = true;
    try {
      // 死 client(底层 stdio 进程已 exit/error,errored 置位;http client 无持久进程恒活)等同无 client:
      // 须释放并重建以重连,否则 supervisor 持有的死 client 永不释放,后续 listTools 写已关闭 stdin,
      // 等满 15s 超时才失败,坏 MCP 反复触发持续拖慢首屏(STAB-02)。
      const deadClient = this.isClientDead();
      if (!this.client || deadClient) {
        if (deadClient) {
          try {
            this.client?.close();
          } catch {}
          this.client = null;
        }
        const newClient = createMcpClient(this.spec);
        await newClient.initialize();
        this.client = newClient;
      }
      // await 后 this.client 字段缩窄会丢失,提取局部 const 并补一次等价守卫
      const client = this.client;
      if (!client) {
        throw new Error(`MCP server not ready: ${this.key}`);
      }
      // listTools() 返回 unknown[](MCP server tools/list 结果结构松散);此处收窄为 RawTool[]
      // 以访问 name/description/inputSchema(_schema)。运行时行为不变。
      const rawTools = /** @type {RawTool[]} */ (await client.listTools());
      this.routeNames.clear();
      this.tools = rawTools.map((tool) => {
        const gatewayName = gatewayToolName(this.spec.sourceProvider, this.spec.serverId, tool.name);
        this.routeNames.set(gatewayName, tool.name);
        return {
          name: gatewayName,
          description: tool.description,
          inputSchema: tool.inputSchema ?? tool.input_schema ?? { type: 'object' },
        };
      });
      this.failureCount = 0;
      this.setHealth('READY', null, Date.now());
      return this.tools;
    } catch (error) {
      this.failureCount += 1;
      this.setHealth(this.tools.length > 0 ? 'DEGRADED' : 'BACKOFF', toErrorMessage(error));
      return this.tools;
    } finally {
      this.refreshing = false;
    }
  }

  /**
   * 调用指定 gateway 工具;首次调用前自动 refresh。
   *
   * @param {string} toolName gateway 工具名
   * @param {unknown} args     工具入参
   * @returns {Promise<unknown>}
   */
  async callTool(toolName, args) {
    if (!this.client) {
      await this.refresh();
    }
    if (!this.client) {
      throw new Error(`MCP server not ready: ${this.key}`);
    }
    return this.client.callTool(this.routeNames.get(toolName) ?? toolName, /** @type {Record<string, unknown>} */ (args));
  }

  /**
   * 关闭客户端并标记 STOPPED。
   *
   * @returns {void}
   */
  stop() {
    try {
      this.client?.close();
    } catch {}
    this.client = null;
    this.setHealth('STOPPED');
  }

  /**
   * 写一条健康记录到 healthStore。
   *
   * @param {string} state
   * @param {string | null} [lastError]
   * @param {number | null} [lastSuccessAt]
   * @returns {void}
   */
  setHealth(state, lastError = null, lastSuccessAt = null) {
    this.healthStore.set(this.key, {
      serverId: this.spec.serverId,
      sourceProvider: this.spec.sourceProvider,
      state,
      lastError,
      lastSuccessAt,
      failureCount: this.failureCount,
    });
  }
}

/**
 * catch 块中 error 为 unknown,统一转成可读字符串(行为等价于原 `error?.message ?? String(error)`,
 * 但避免在 unknown 上直接读属性触发 TS2571)。
 *
 * @param {unknown} error
 * @returns {string}
 */
function toErrorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}
