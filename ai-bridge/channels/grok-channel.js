// @ts-check
/**
 * Grok channel command handler – keeps Grok-specific logic separated.
 *
 * 会话发送已走 GrokRunOnceCliSession(直 spawn 原生 CLI headless streaming-json),
 * 此处仅保留 listModels,供 channel-manager.js dispatch。
 * 沿用 opencode-channel 同一清理范式(send 分支随直 spawn 化移除)。
 */
import { listModels as grokListModels } from '../services/grok/models-service.js';

/**
 * Execute a Grok command.
 * @param {string} command
 * @param {string[]} args
 * @param {Record<string, any> | null} stdinData
 */
export async function handleGrokCommand(command, args, stdinData) {
  switch (command) {
    case 'listModels':
      grokListModels();
      break;

    default:
      throw new Error(`Unknown Grok command: ${command}`);
  }
}

export function getGrokCommandList() {
  return ['listModels'];
}

export const grokChannelDescriptor = {
  provider: 'grok',
  commands: getGrokCommandList(),
  handle: handleGrokCommand,
};
