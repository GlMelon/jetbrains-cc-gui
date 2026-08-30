// @ts-check
import { removeStateFile } from './state-file.js';

/**
 * Install idempotent signal shutdown handlers and return the shared shutdown entry.
 *
 * @param {{
 *   ipc: { close: () => Promise<void> };
 *   stateFile: string;
 *   processRef?: Pick<NodeJS.Process, 'on' | 'exit'>;
 *   logger?: Pick<Console, 'error'>;
 *   removeState?: (stateFile: string) => void;
 * }} options
 * @returns {(signal: NodeJS.Signals) => Promise<void>}
 */
export function installGatewayShutdown({
  ipc,
  stateFile,
  processRef = process,
  logger = console,
  removeState = removeStateFile,
}) {
  /** @type {Promise<void> | null} */
  let shutdownPromise = null;

  /** @param {NodeJS.Signals} signal */
  const shutdown = (signal) => {
    if (shutdownPromise) return shutdownPromise;
    shutdownPromise = ipc.close()
      .then(() => {
        removeState(stateFile);
        processRef.exit(0);
      })
      .catch((error) => {
        logger.error(`[WARN][mcp-gateway] graceful shutdown failed after ${signal}:`, error);
        removeState(stateFile);
        processRef.exit(1);
      });
    return shutdownPromise;
  };

  for (const signal of /** @type {NodeJS.Signals[]} */ (['SIGINT', 'SIGTERM'])) {
    processRef.on(signal, () => {
      void shutdown(signal);
    });
  }
  processRef.on('exit', () => removeState(stateFile));
  return shutdown;
}