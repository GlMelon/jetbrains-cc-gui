export class HttpMcpClient {
  // 单次 JSON-RPC 请求默认超时(ms)。慢/挂的 HTTP MCP server 不应无限期等待,否则拖垮整个
  // gateway catalog refresh。config.request_timeout_ms 或 request 第三参可覆盖。
  static DEFAULT_REQUEST_TIMEOUT_MS = 15000;

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
    const result = await this.request('tools/list', {});
    return result?.tools ?? [];
  }

  async callTool(name, args) {
    return this.request('tools/call', { name, arguments: args ?? {} });
  }

  async request(method, params, timeoutMs) {
    const url = this.config.url;
    if (!url) throw new Error(`Missing HTTP MCP url for ${this.spec.serverId}`);
    const timeout = timeoutMs ?? this.requestTimeoutMs ?? HttpMcpClient.DEFAULT_REQUEST_TIMEOUT_MS;
    const headers = { 'content-type': 'application/json', ...(this.config.http_headers ?? {}) };
    const tokenEnv = this.config.bearer_token_env_var;
    if (tokenEnv && process.env[tokenEnv]) {
      headers.authorization = `Bearer ${process.env[tokenEnv]}`;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
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
      const message = await response.json();
      if (message.error) {
        throw new Error(message.error.message ?? 'MCP error');
      }
      return message.result;
    } catch (e) {
      // AbortController 超时:不同运行时抛 AbortError/DOMException,统一转超时错误
      if (controller.signal.aborted || e?.name === 'AbortError') {
        throw new Error(`HTTP MCP request timeout: ${method} (${this.spec.serverId}) after ${timeout}ms`);
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  close() {
  }
}
