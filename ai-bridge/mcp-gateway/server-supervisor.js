import { createMcpClient } from './transport/client-factory.js';
import { gatewayToolName } from './tool-router.js';

export class ServerSupervisor {
  constructor(spec, healthStore) {
    this.spec = spec;
    this.healthStore = healthStore;
    this.key = `${spec.sourceProvider}:${spec.serverId}`;
    this.client = null;
    this.tools = [];
    this.routeNames = new Map();
    this.failureCount = 0;
    this.refreshing = false;
    this.setHealth('STARTING');
  }

  async refresh() {
    if (this.refreshing) return this.tools;
    this.refreshing = true;
    try {
      if (!this.client) {
        this.client = createMcpClient(this.spec);
        await this.client.initialize();
      }
      const rawTools = await this.client.listTools();
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
      this.setHealth(this.tools.length > 0 ? 'DEGRADED' : 'BACKOFF', error?.message ?? String(error));
      return this.tools;
    } finally {
      this.refreshing = false;
    }
  }

  async callTool(toolName, args) {
    if (!this.client) {
      await this.refresh();
    }
    if (!this.client) {
      throw new Error(`MCP server not ready: ${this.key}`);
    }
    return this.client.callTool(this.routeNames.get(toolName) ?? toolName, args);
  }

  stop() {
    try {
      this.client?.close();
    } catch {}
    this.client = null;
    this.setHealth('STOPPED');
  }

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
