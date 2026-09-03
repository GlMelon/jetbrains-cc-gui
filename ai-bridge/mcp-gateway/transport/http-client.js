// @ts-check
/**
 * @typedef {{ url?: string; http_headers?: Record<string, string>; bearer_token_env_var?: string; request_timeout_ms?: number }} HttpMcpConfig
 */

/**
 * @typedef {{ serverId: string; transport?: 'http' | 'sse'; config?: HttpMcpConfig }} HttpMcpSpec
 */

export class HttpMcpClient {
  // 单次 JSON-RPC 请求默认超时(ms)。慢/挂的 HTTP MCP server 不应无限期等待,否则拖垮整个
  // gateway catalog refresh。config.request_timeout_ms 或 request 第三参可覆盖。
  static DEFAULT_REQUEST_TIMEOUT_MS = 15000;
  // tools/call 专用默认超时(ms):与 stdio-client 对称,慢工具不在外腿(gateway 60s)到时前被
  // 内腿 15s 误杀;用户显式配置的 config.request_timeout_ms 仍最优先。
  static CALL_TOOL_TIMEOUT_MS = 55000;

  /** @param {HttpMcpSpec} spec */
  constructor(spec) {
    this.spec = spec;
    this.config = spec.config ?? {};
    this.requestTimeoutMs = typeof this.config.request_timeout_ms === 'number'
      ? this.config.request_timeout_ms
      : null;
    this.nextId = 1;
  }

  async initialize() {
    await this.request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'melon-mcp-gateway', version: '1.0.0' },
    });
  }

  async listTools() {
    const result = /** @type {{ tools?: unknown[] }} */ (await this.request('tools/list', {}));
    return result?.tools ?? [];
  }

  /**
   * @param {string} name
   * @param {Record<string, unknown> | null | undefined} args
   * @param {AbortSignal} [signal] 取消信号(gateway 侧客户端断开透传)
   */
  async callTool(name, args, signal) {
    return this.request('tools/call', { name, arguments: args ?? {} },
      this.requestTimeoutMs ?? HttpMcpClient.CALL_TOOL_TIMEOUT_MS, signal);
  }

  /**
   * @param {string} method JSON-RPC method
   * @param {Record<string, unknown>} params JSON-RPC params
   * @param {number} [timeoutMs] 可选单次超时覆盖
   * @param {AbortSignal} [signal] 取消信号(abort 时中止 fetch,报 cancelled 而非 timeout)
   * @returns {Promise<unknown>} JSON-RPC result 字段
   */
  async request(method, params, timeoutMs, signal) {
    const url = this.config.url;
    if (!url) throw new Error(`Missing HTTP MCP url for ${this.spec.serverId}`);
    const timeout = timeoutMs ?? this.requestTimeoutMs ?? HttpMcpClient.DEFAULT_REQUEST_TIMEOUT_MS;
    /** @type {Record<string, string>} */
    const headers = { 'content-type': 'application/json', ...(this.config.http_headers ?? {}) };
    const tokenEnv = this.config.bearer_token_env_var;
    if (tokenEnv && process.env[tokenEnv]) {
      headers.authorization = `Bearer ${process.env[tokenEnv]}`;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    // 取消传播:外部 signal(CLI 会话中断经 gateway HTTP 断开透传)abort 时联动本请求 controller
    const onExternalAbort = () => controller.abort();
    if (signal?.aborted) {
      clearTimeout(timer);
      throw new Error(`HTTP MCP request cancelled: ${method} (${this.spec.serverId})`);
    }
    signal?.addEventListener('abort', onExternalAbort, { once: true });
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify({ jsonrpc: '2.0', id: this.nextId++, method, params }),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`HTTP MCP ${response.status}`);
      }
      const message = /** @type {{ error?: { message?: string }; result?: unknown }} */ (await response.json());
      if (message.error) {
        throw new Error(message.error.message ?? 'MCP error');
      }
      return message.result;
    } catch (e) {
      // 外部取消(gateway 侧客户端断开)优先于超时判定:报 cancelled 而非 timeout
      if (signal?.aborted) {
        throw new Error(`HTTP MCP request cancelled: ${method} (${this.spec.serverId})`);
      }
      // AbortController 超时:不同运行时抛 AbortError/DOMException,统一转超时错误
      const errName = e instanceof Error ? e.name : '';
      if (controller.signal.aborted || errName === 'AbortError') {
        throw new Error(`HTTP MCP request timeout: ${method} (${this.spec.serverId}) after ${timeout}ms`);
      }
      throw e;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onExternalAbort);
    }
  }

  close() {
  }
}
