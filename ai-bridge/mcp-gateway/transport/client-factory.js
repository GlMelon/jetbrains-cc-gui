import { StdioMcpClient } from './stdio-client.js';
import { HttpMcpClient } from './http-client.js';

export function createMcpClient(spec) {
  const transport = spec.transport ?? 'stdio';
  if (transport === 'http' || transport === 'sse') {
    return new HttpMcpClient(spec);
  }
  return new StdioMcpClient(spec);
}
