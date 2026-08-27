// @ts-check
/**
 * Kimi channel command handler – keeps Kimi-specific logic separated.
 *
 * 会话发送已走 KimiRunOnceCliSession(直 spawn 原生 CLI stream-json),
 * 此处仅保留 listModels,供 channel-manager.js dispatch。
 */
import { listModels as kimiListModels } from '../services/kimi/models-service.js';

/**
 * Execute a Kimi command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 */
export async function handleKimiCommand(command, args, stdinData) {
  switch (command) {
    case 'listModels':
      kimiListModels();
      break;

    default:
      throw new Error(`Unknown Kimi command: ${command}`);
  }
}

export function getKimiCommandList() {
  return ['listModels'];
}

export const kimiChannelDescriptor = {
  provider: 'kimi',
  commands: getKimiCommandList(),
  handle: handleKimiCommand,
};
