// @ts-check
import { StdioMcpClient } from './stdio-client.js';
import { HttpMcpClient } from './http-client.js';

/**
 * 按 spec.transport 选择 stdio 或 http/sse 客户端。
 *
 * catalog 下发的 spec 是松散对象(transport/config 形态未收窄);factory 在此按 transport
 * 分派,具体 config 字段的解释与校验由对应 client 构造器负责,故此处用 ConstructorParameters
 * 把 spec 收窄到目标 client 期望的入参类型。
 *
 * @param {{ serverId: string; transport?: string; config?: Record<string, unknown> }} spec server 规格
 * @returns {StdioMcpClient | HttpMcpClient} 对应传输的 MCP 客户端实例
 */
export function createMcpClient(spec) {
  const transport = spec.transport ?? 'stdio';
  if (transport === 'http' || transport === 'sse') {
    return new HttpMcpClient(/** @type {ConstructorParameters<typeof HttpMcpClient>[0]} */ (spec));
  }
  return new StdioMcpClient(/** @type {ConstructorParameters<typeof StdioMcpClient>[0]} */ (spec));
}
