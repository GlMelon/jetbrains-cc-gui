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
    /**
     * 退避冷却到期时刻(ms epoch);到期前 refresh() 直接返回现状,不重 spawn。
     * @type {number}
     */
    this.nextAttemptAt = 0;
    /** @type {boolean} */
    this.refreshing = false;
    /** @type {boolean} */
    this.stopping = false;
    /** @type {Promise<void> | null} */
    this.stopPromise = null;
    /**
     * 上一次 refresh 是否成功(工具列表可信)。供 IpcServer.applySnapshot 做选择性刷新:
     * 健康且配置未变的 supervisor 跳过重刷,不再每次配置推送都对全部 server 重跑 listTools。
     * @type {boolean}
     */
    this.healthy = false;
    /**
     * server 主动推送 notifications/tools/list_changed 时置位(仅 stdio 传输可收通知),
     * 下次 applySnapshot 据此重刷该 supervisor;refresh 成功后清零。
     * @type {boolean}
     */
    this.toolsStale = false;
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
   * <p>失败退避:此前 BACKOFF 只是健康状态标签,失败后下一次 refresh() 立即重 spawn(零延迟、
   * 零上限),每次 MCP 配置变更/gateway 重载/坏工具调用都会并发重 spawn 所有失败 server。
   * 现在 initialize/listTools 失败后进入指数退避冷却(见 {@link backoffDelayMs}),冷却期内
   * refresh() 直接返回现有 tools 不 spawn;成功或 stop() 清零。
   *
   * @returns {Promise<GatewayTool[]>}
   */
  async refresh() {
    if (this.stopping) return this.tools;
    if (this.refreshing) return this.tools;
    if (Date.now() < this.nextAttemptAt) {
      // 退避冷却期内:不 spawn,返回现状(健康状态保持 BACKOFF/DEGRADED 不变)
      return this.tools;
    }
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
        try {
          await newClient.initialize();
          if (this.stopping) {
            await newClient.close();
            return this.tools;
          }
        } catch (err) {
          // initialize 失败(典型:npx 型 server 首次下载超过 15s 超时)时,createMcpClient 已在构造器内
          // spawn 的子进程必须先 close 再 rethrow——否则 newClient 无任何引用、优雅关停也杀不到它,
          // 子进程泄漏,且 this.client 保持 null,下次 refresh/applySnapshot 会再 spawn 一个。
          try {
            newClient.close();
          } catch {}
          throw err;
        }
        this.client = newClient;
        // stdio server 支持 notifications/tools/list_changed 推送:收到即标记工具缓存过期,
        // 下次 applySnapshot 选择性重刷该 supervisor(代替每次配置推送全量重 listTools)。
        // http client 无持久连接收不到通知,'in' 守卫不触碰其不存在的字段(范式同 isClientDead)。
        if ('onToolsListChanged' in newClient) {
          newClient.onToolsListChanged = () => {
            this.toolsStale = true;
          };
        }
      }
      // await 后 this.client 字段缩窄会丢失,提取局部 const 并补一次等价守卫
      const client = this.client;
      if (!client) {
        throw new Error(`MCP server not ready: ${this.key}`);
      }
      // listTools() 返回 unknown[](MCP server tools/list 结果结构松散);此处收窄为 RawTool[]
      // 以访问 name/description/inputSchema(_schema)。运行时行为不变。
      const rawTools = /** @type {RawTool[]} */ (await client.listTools());
      if (this.stopping) return this.tools;
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
      if (this.stopping) return this.tools;
      this.failureCount = 0;
      this.nextAttemptAt = 0; // 成功:清退避冷却,立即恢复
      this.healthy = true;
      this.toolsStale = false;
      this.setHealth('READY', null, Date.now());
      return this.tools;
    } catch (error) {
      if (this.stopping) return this.tools;
      this.failureCount += 1;
      // 真指数退避:失败次数越多冷却越长,冷却期内不再 spawn(此前 BACKOFF 仅是标签,立即重 spawn)
      this.nextAttemptAt = Date.now() + backoffDelayMs(this.failureCount);
      this.healthy = false;
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
   * @param {AbortSignal} [signal] 取消信号(CLI 会话中断经 gateway HTTP 断开透传而来)
   * @returns {Promise<unknown>}
   */
  async callTool(toolName, args, signal) {
    if (!this.client) {
      await this.refresh();
    }
    if (!this.client) {
      throw new Error(`MCP server not ready: ${this.key}`);
    }
    return this.client.callTool(this.routeNames.get(toolName) ?? toolName, /** @type {Record<string, unknown>} */ (args), signal);
  }

  /**
   * 关闭客户端并标记 STOPPED。重复调用共享同一个关闭 Promise。
   *
   * @returns {Promise<void>}
   */
  stop() {
    if (this.stopPromise) return this.stopPromise;
    this.stopping = true;
    const client = this.client;
    this.client = null;
    this.failureCount = 0;
    this.nextAttemptAt = 0;
    this.healthy = false;
    this.toolsStale = false;
    this.stopPromise = Promise.resolve()
      .then(() => client?.close())
      .catch(() => {})
      .then(() => {
        this.setHealth('STOPPED');
      });
    return this.stopPromise;
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

/** 指数退避基数(ms)。第 1 次失败 = 1s,2 = 2s,3 = 4s ... 递增。 */
const BACKOFF_BASE_MS = 1_000;
/** 退避上限(ms):坏 server 的连续失败冷却封顶在 5 分钟(与 Java 侧熔断 half-open 探试窗一致)。 */
const BACKOFF_MAX_MS = 5 * 60_000;

/**
 * 指数退避延迟(ms):base * 2^(n-1),封顶在 {@link BACKOFF_MAX_MS}。
 * 纯函数(无 Date.now 依赖),便于单测。
 *
 * @param {number} failureCount 连续失败次数(≥1)
 * @returns {number} 冷却毫秒数
 */
export function backoffDelayMs(failureCount) {
  if (failureCount <= 0) return 0;
  const delay = BACKOFF_BASE_MS * Math.pow(2, failureCount - 1);
  return Math.min(delay, BACKOFF_MAX_MS);
}
