// @ts-check
/**
 * MiniMax Code channel command handler – keeps MiniMax-specific logic separated.
 *
 * 会话发送已走 MiniMaxRunOnceCliSession(直 spawn 原生 CLI `exec --output-format
 * stream-json`),此处仅保留 listModels,供 channel-manager.js dispatch。
 */
import { listModels as minimaxListModels } from '../services/minimax/models-service.js';

/**
 * Execute a MiniMax command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 */
export async function handleMiniMaxCommand(command, args, stdinData) {
  switch (command) {
    case 'listModels':
      minimaxListModels();
      break;

    default:
      throw new Error(`Unknown MiniMax command: ${command}`);
  }
}

export function getMiniMaxCommandList() {
  return ['listModels'];
}

export const minimaxChannelDescriptor = {
  provider: 'minimax',
  commands: getMiniMaxCommandList(),
  handle: handleMiniMaxCommand,
};
