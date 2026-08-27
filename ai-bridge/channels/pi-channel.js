// @ts-check
/**
 * PI channel command handler – keeps PI-specific logic separated.
 *
 * 会话发送已走 PiRunOnceCliSession(直 spawn 原生 CLI --print --mode json),
 * 此处仅保留 listModels,供 channel-manager.js dispatch。
 */
import { listModels as piListModels } from '../services/pi/models-service.js';

/**
 * Execute a PI command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 */
export async function handlePiCommand(command, args, stdinData) {
  switch (command) {
    case 'listModels':
      piListModels();
      break;

    default:
      throw new Error(`Unknown PI command: ${command}`);
  }
}

export function getPiCommandList() {
  return ['listModels'];
}

export const piChannelDescriptor = {
  provider: 'pi',
  commands: getPiCommandList(),
  handle: handlePiCommand,
};
