// @ts-check
// gateway-http-client.js — gateway-stdio-client.js 连本地 gateway HTTP 的客户端 + tools/list 降级。
//
// 背景(30s 根因):gateway-stdio-client.js 原 request() 用 http.request 无任何 socket/connect 超时,
// gateway 进程崩溃/重启中时端口陈旧 → tools/list 连死 TCP 挂死 → provider 等满 30s initialize
// 超时才标记 status=failed(opencode 最慢,Claude/Codex 次之)。提取独立类:
//   ① 注入超时(默认 5s,比 transport/http-client.js:4 的 15s 短——gateway 本地 127.0.0.1 该秒回,
//      stdio/http-client 的 15s 是给真实外部 MCP server 慢启动留余量);
//   ② fetch + AbortController 范式直接复用 transport/http-client.js:41-66(统一 AbortError 转 timeout);
//   ③ 把 tools/list handle 提取为 runToolsList 纯函数,gateway 不可达时降级返 {tools:[]} + stderr 标记,
//      让对话继续(用户失去 MCP 工具但不再等 30s),前端 toast 由 Java 侧 [melon-gateway-down] 标记上行。
//
// 协议差异(为何不直接复用 HttpMcpClient):gateway 是多路径 REST(/runtime/tools/list、
// /runtime/tools/call)+ GET(list)/POST(call)+ Bearer token + 裸 result;HttpMcpClient 是
// 单 url JSON-RPC envelope POST({jsonrpc,id,method,params}→{result})。

import { writeMessage } from './framing.js';

/** @typedef {import('./framing.js').McpWritable} McpWritable */

export class GatewayHttpClient {
  // 单次请求默认超时(ms)。gateway 本地 HTTP,5s 足够;挂死 TCP 不再拖 30s。
  static DEFAULT_TIMEOUT_MS = 5000;
  // tools/call 超时(ms)。工具执行(DB 查询/Web 抓取/代码执行)远比 tools/list(本地秒回)慢,
  // 不能复用 5s——>5s 的工具调用会被误判为失败并报 JSON-RPC error(MCP-02)。60s 覆盖绝大多数工具。
  static TOOLS_CALL_TIMEOUT_MS = 60_000;

  /**
   * @param {{ host?: string; port?: number; token?: string; timeoutMs?: number }} [opts]
   */
  constructor({ host = '127.0.0.1', port, token, timeoutMs } = {}) {
    if (!port) throw new Error('GatewayHttpClient: port required');
    /** @type {string} */
    this.host = host;
    /** @type {number} */
    this.port = port;
    /** @type {string | undefined} */
    this.token = token;
    /** @type {number} */
    this.timeoutMs = typeof timeoutMs === 'number' ? timeoutMs : GatewayHttpClient.DEFAULT_TIMEOUT_MS;
  }

  /**
   * @param {string} method    HTTP 方法(GET/POST)
   * @param {string} path      路径(含 query)
   * @param {object | null | undefined} body 请求体(POST)
   * @param {number | undefined} [timeoutMs] 单次超时覆盖
   * @returns {Promise<any>} 解析后的 JSON 响应
   */
  async request(method, path, body, timeoutMs) {
    const timeout = timeoutMs ?? this.timeoutMs;
    const url = `http://${this.host}:${this.port}${path}`;
    const payload = body ? JSON.stringify(body) : null;
    const headers = {
      authorization: `Bearer ${this.token}`,
      ...(payload ? { 'content-type': 'application/json' } : {}),
    };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(url, {
        method,
        headers,
        body: payload,
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`Gateway HTTP ${response.status}`);
      }
      const text = await response.text();
      return text ? JSON.parse(text) : {};
    } catch (e) {
      // AbortController 超时:不同运行时抛 AbortError/DOMException/TypeError,统一转超时错误
      if (controller.signal.aborted || (e instanceof Error && e.name === 'AbortError')) {
        throw new Error(`Gateway HTTP timeout: ${method} ${path} after ${timeout}ms`);
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * @param {string} path
   * @param {number | undefined} [timeoutMs]
   * @returns {Promise<any>}
   */
  get(path, timeoutMs) { return this.request('GET', path, null, timeoutMs); }

  /**
   * @param {string} path
   * @param {object | null | undefined} body
   * @param {number | undefined} [timeoutMs]
   * @returns {Promise<any>}
   */
  post(path, body, timeoutMs) { return this.request('POST', path, body, timeoutMs); }
}

/**
 * 处理 tools/list JSON-RPC 请求:gateway 可达时转发真实工具列表,不可达时降级返空工具列表。
 *
 * <p>降级语义:gateway 崩溃/重启中时,返 {tools:[]} 让 provider 握手继续(用户失去 MCP 工具但
 * 不再等 30s),stderr 写 [melon-gateway-down] 标记供 Java 侧上行前端 toast(见 GatewayDownMatcher)。
 * 成功路径返回真实 tools;失败路径绝不返 JSON-RPC error(原实现返 error 让 provider 标记失败)。
 *
 * @param {{ httpClient: GatewayHttpClient; revision: number; message: { method?: string; id?: unknown; [k: string]: unknown } | null | undefined; output: McpWritable; stderr?: McpWritable }} args
 * @returns {Promise<boolean>} true 表示已处理 tools/list(调用方不再走基类)
 */
export async function runToolsList({ httpClient, revision, message, output, stderr }) {
  if (!message || message.method !== 'tools/list') return false;
  const errSink = stderr ?? process.stderr;
  try {
    const result = await httpClient.get(`/runtime/tools/list?revision=${encodeURIComponent(revision)}`);
    writeMessage(output, { jsonrpc: '2.0', id: message.id, result });
  } catch (error) {
    errSink.write(`[melon-gateway-down] tools/list degraded to empty (gateway unreachable): ${error instanceof Error ? error.message : String(error)}\n`);
    writeMessage(output, { jsonrpc: '2.0', id: message.id, result: { tools: [] } });
  }
  return true;
}
