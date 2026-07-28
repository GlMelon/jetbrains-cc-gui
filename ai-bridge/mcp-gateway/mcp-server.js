// @ts-check
import { writeMessage } from './framing.js';
import { RevisionStore } from './revision-store.js';
import { ToolRouter } from './tool-router.js';

/** @typedef {import('./framing.js').McpWritable} McpWritable */

/**
 * JSON-RPC 请求消息的最小形状(method/id/params 字段在通知类消息中可能缺失)。
 * @typedef {{ jsonrpc?: string; id?: unknown; method?: string; params?: Record<string, unknown> } & Record<string, unknown>} JsonRpcMessage
 */

export class GatewayMcpServer {
  /**
   * @param {{ revisionStore: RevisionStore; toolRouter: ToolRouter; revision: number | string }} opts
   */
  constructor({ revisionStore, toolRouter, revision }) {
    /** @type {RevisionStore} */
    this.revisionStore = revisionStore;
    /** @type {ToolRouter} */
    this.toolRouter = toolRouter;
    /** @type {number} */
    this.revision = Number(revision || 0);
  }

  /**
   * @param {JsonRpcMessage | null | undefined} message
   * @param {McpWritable} output
   * @returns {Promise<void>}
   */
  async handle(message, output) {
    if (!message || !message.method) return;
    try {
      if (message.method === 'initialize') {
        this.respond(output, message.id, {
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'melon-mcp-gateway', version: '1.0.0' },
        });
        return;
      }
      if (message.method === 'notifications/initialized') {
        return;
      }
      if (message.method === 'tools/list') {
        const catalog = this.revisionStore.get(this.revision);
        this.respond(output, message.id, { tools: catalog.tools ?? [] });
        return;
      }
      if (message.method === 'tools/call') {
        const params = message.params ?? {};
        const result = await this.toolRouter.call(/** @type {string} */ (params.name), params.arguments ?? {}, this.revision);
        this.respond(output, message.id, result);
        return;
      }
      this.error(output, message.id, -32601, `Method not found: ${message.method}`);
    } catch (error) {
      this.error(output, message.id, -32000, error instanceof Error ? error.message : String(error));
    }
  }

  /**
   * @param {McpWritable} output
   * @param {unknown} id
   * @param {unknown} result
   * @returns {void}
   */
  respond(output, id, result) {
    if (id === undefined || id === null) return;
    writeMessage(output, { jsonrpc: '2.0', id, result });
  }

  /**
   * @param {McpWritable} output
   * @param {unknown} id
   * @param {number} code
   * @param {string} message
   * @returns {void}
   */
  error(output, id, code, message) {
    if (id === undefined || id === null) return;
    writeMessage(output, { jsonrpc: '2.0', id, error: { code, message } });
  }
}
