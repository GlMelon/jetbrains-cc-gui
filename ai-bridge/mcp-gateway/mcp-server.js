import { writeMessage } from './framing.js';

export class GatewayMcpServer {
  constructor({ revisionStore, toolRouter, revision }) {
    this.revisionStore = revisionStore;
    this.toolRouter = toolRouter;
    this.revision = Number(revision || 0);
  }

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
        const result = await this.toolRouter.call(params.name, params.arguments ?? {}, this.revision);
        this.respond(output, message.id, result);
        return;
      }
      this.error(output, message.id, -32601, `Method not found: ${message.method}`);
    } catch (error) {
      this.error(output, message.id, -32000, error?.message ?? String(error));
    }
  }

  respond(output, id, result) {
    if (id === undefined || id === null) return;
    writeMessage(output, { jsonrpc: '2.0', id, result });
  }

  error(output, id, code, message) {
    if (id === undefined || id === null) return;
    writeMessage(output, { jsonrpc: '2.0', id, error: { code, message } });
  }
}
