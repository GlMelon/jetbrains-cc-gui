// @ts-check
/**
 * HTTP/Streamable HTTP tools retrieval with bounded response parsing,
 * absolute timeout and MCP session recovery.
 */

import { MCP_TOOLS_TIMEOUT } from './config.js';
import { log } from './logger.js';
import {
  MCP_CLIENT_INFO,
  MCP_PROTOCOL_VERSION,
  buildSseRequestContext,
  cancelResponseBody,
  readJsonRpcResponse
} from './mcp-protocol.js';

/** @type {number} */
const MAX_NETWORK_RETRIES = 2;
/** @type {number} */
const MAX_SESSION_RESTARTS = 1;

class McpSessionInvalidError extends Error {
  /**
   * @param {string} message
   */
  constructor(message) {
    super(message);
    this.name = 'McpSessionInvalidError';
  }
}

/**
 * 判断错误是否属于 MCP session 失效错误(code -32600 或消息含 "session")。
 * 参数设计上接受任意 error-like 值(可能带 message/code 属性),故标 any 以保留原动态访问。
 * @param {any} error
 * @returns {boolean}
 */
function isSessionError(error) {
  const message = String(error?.message || '').toLowerCase();
  return error?.code === -32600 || message.includes('session');
}

/**
 * 判断错误是否属于可重试的网络类错误。
 * @param {any} error
 * @returns {boolean}
 */
function isRetryableNetworkError(error) {
  const message = String(error?.message || '').toLowerCase();
  return message.includes('econnrefused') ||
    message.includes('fetch failed') ||
    message.includes('socket') ||
    message.includes('network');
}

/**
 * @param {number} delayMs
 * @param {AbortSignal} signal
 * @returns {Promise<void>}
 */
async function abortableDelay(delayMs, signal) {
  if (signal.aborted) throw signal.reason;
  await new Promise((/** @type {(value: void) => void} */ resolve, /** @type {(reason?: unknown) => void} */ reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, delayMs);
    const onAbort = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
      reject(signal.reason);
    };
    signal.addEventListener('abort', onAbort, { once: true });
    timer.unref?.();
  });
}

/**
 * Retrieve the tool list from an HTTP/Streamable HTTP MCP server.
 * @param {string} serverName - Server name
 * @param {Record<string, any>} serverConfig - Server configuration
 * @returns {Promise<{ name: string, tools: any[], error: string | null, serverType: string }>} Tools list response
 */
export async function getHttpServerTools(serverName, serverConfig) {
  /** @type {{ name: string, tools: any[], error: string | null, serverType: string }} */
  const result = {
    name: serverName,
    tools: [],
    error: null,
    serverType: serverConfig.type || 'sse'
  };

  if (!serverConfig.url) {
    result.error = 'No URL specified for HTTP/SSE server';
    return result;
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(
    () => controller.abort(new DOMException('MCP tools request timed out', 'AbortError')),
    MCP_TOOLS_TIMEOUT
  );
  timeoutId.unref?.();

  const { fetchUrl, headers: configuredHeaders } = buildSseRequestContext(
    serverConfig.url,
    serverConfig
  );
  /** @type {Record<string, string>} */
  const baseHeaders = {
    ...configuredHeaders,
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream'
  };

  let requestId = 0;
  /** @type {string | null} */
  let sessionId = null;

  /**
   * @param {string} method
   * @param {Record<string, any>} [params]
   * @param {number} [retryCount]
   */
  const sendRequest = async (method, params = {}, retryCount = 0) => {
    const id = ++requestId;
    const request = { jsonrpc: '2.0', id, method, params };
    const headers = { ...baseHeaders };
    if (sessionId) headers['Mcp-Session-Id'] = sessionId;

    try {
      const response = await fetch(fetchUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify(request),
        signal: controller.signal
      });

      if (!response.ok) {
        await cancelResponseBody(response);
        throw new Error('HTTP ' + response.status + ': ' + response.statusText);
      }

      const responseSessionId = response.headers.get('Mcp-Session-Id');
      if (responseSessionId) sessionId = responseSessionId;

      const data = await readJsonRpcResponse(
        response,
        id,
        controller.signal,
        method + ' response'
      );
      if (data?.error) {
        const message = data.error.message || JSON.stringify(data.error);
        if (isSessionError(data.error)) throw new McpSessionInvalidError(message);
        throw new Error('Server error: ' + message);
      }
      return data;
    } catch (error) {
      if (!controller.signal.aborted &&
          !(error instanceof McpSessionInvalidError) &&
          isRetryableNetworkError(error) &&
          retryCount < MAX_NETWORK_RETRIES) {
        log('warn', '[MCP Tools] Network error, retrying:', error instanceof Error ? error.message : String(error));
        await abortableDelay(500 * (retryCount + 1), controller.signal);
        return sendRequest(method, params, retryCount + 1);
      }
      throw error;
    }
  };

  const sendInitializedNotification = async () => {
    const headers = { ...baseHeaders };
    if (sessionId) headers['Mcp-Session-Id'] = sessionId;
    const response = await fetch(fetchUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
      signal: controller.signal
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status + ': ' + response.statusText);
    }
    try { await response.body?.cancel(); } catch { /* best effort */ }
  };

  try {
    log('info', '[MCP Tools] Starting tools fetch for HTTP server:', serverName);

    for (let sessionAttempt = 0; sessionAttempt <= MAX_SESSION_RESTARTS; sessionAttempt++) {
      sessionId = null;
      const initResponse = await sendRequest('initialize', {
        protocolVersion: MCP_PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: MCP_CLIENT_INFO
      });
      if (!initResponse?.result) {
        throw new Error('Invalid initialize response: missing result');
      }

      try {
        await sendInitializedNotification();
      } catch (error) {
        if (controller.signal.aborted) throw error;
        log('debug', '[MCP Tools] initialized notification failed for', serverName, error instanceof Error ? error.message : String(error));
      }

      try {
        const toolsResponse = await sendRequest('tools/list', {});
        result.tools = Array.isArray(toolsResponse?.result?.tools)
          ? toolsResponse.result.tools
          : [];
        log('info', '[MCP Tools] HTTP server', serverName, 'returned', result.tools.length, 'tools');
        break;
      } catch (error) {
        if (!(error instanceof McpSessionInvalidError) || sessionAttempt >= MAX_SESSION_RESTARTS) {
          throw error;
        }
        log('warn', '[MCP Tools] Session invalid, repeating initialize handshake:', serverName);
      }
    }
  } catch (error) {
    result.error = controller.signal.aborted
      ? 'Connection timeout'
      : (error instanceof Error ? error.message : String(error));
    log('error', '[MCP Tools] HTTP server', serverName, 'failed:', result.error);
  } finally {
    clearTimeout(timeoutId);
    controller.abort();
  }

  return result;
}
