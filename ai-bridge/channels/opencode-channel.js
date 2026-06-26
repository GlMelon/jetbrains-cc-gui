/**
 * OpenCode channel command handler – keeps OpenCode specific logic separated.
 *
 * OpenCode uses HTTP REST API (opencode serve) for communication.
 * This channel handler manages the HTTP client interaction with OpenCode.
 */

/**
 * Execute an OpenCode command.
 * @param {string} command
 * @param {string[]} args
 * @param {object|null} stdinData
 */
export async function handleOpenCodeCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        const {
          message,
          threadId,
          cwd,
          permissionMode,
          model,
          reasoningEffort,
          attachments
        } = stdinData;
        await openCodeSendMessage(
          message,
          threadId || '',
          cwd || '',
          permissionMode || '',
          model || '',
          reasoningEffort || 'medium',
          attachments || []
        );
      } else {
        await openCodeSendMessage(args[0], args[1], args[2], args[3], args[4]);
      }
      break;
    }

    case 'getMcpServerTools': {
      // OpenCode MCP support - placeholder for future implementation
      const serverId = stdinData?.serverId || args[0] || null;
      console.log(`[OpenCode] getMcpServerTools: ${serverId}`);
      break;
    }

    default:
      throw new Error(`Unknown OpenCode command: ${command}`);
  }
}

/**
 * Send a message to OpenCode via HTTP API.
 */
async function openCodeSendMessage(
  message,
  threadId,
  cwd,
  permissionMode,
  model,
  reasoningEffort,
  attachments
) {
  // OpenCode HTTP API communication will be implemented here
  // For now, output a placeholder event
  const event = {
    type: 'error',
    message: 'OpenCode SDK mode not yet fully implemented. Use CLI mode instead.'
  };
  process.stdout.write(JSON.stringify(event) + '\n');
}

export function getOpenCodeCommandList() {
  return ['send', 'getMcpServerTools'];
}

export const opencodeChannelDescriptor = {
  provider: 'opencode',
  commands: getOpenCodeCommandList(),
  handle: handleOpenCodeCommand,
};
