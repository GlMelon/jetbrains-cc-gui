// @ts-check
/**
 * Process management module
 * Provides process creation, event handling, and safe termination
 */

import { log } from './logger.js';
import { parseServerInfo } from './server-info-parser.js';
import { hasValidMcpResponse, createInitializeRequest } from './mcp-protocol.js';
import { killChildTree } from '../../../utils/kill-tree.js';

const MAX_PROCESS_OUTPUT_BUFFER_SIZE = 1024 * 1024;

/**
 * Finalization callback signature invoked when the process reaches a terminal state.
 * @typedef {(status: string, serverInfo?: any, error?: string | null) => void} FinalizeCallback
 */

/**
 * Context passed to {@link createProcessHandlers}.
 * @typedef {{
 *   serverName: string;
 *   child: import('child_process').ChildProcess | null;
 *   finalize: FinalizeCallback;
 * }} ProcessHandlerContext
 */

/**
 * Collection of process event handlers returned by {@link createProcessHandlers}.
 * @typedef {{
 *   stdout: { onData: (data: Buffer) => void };
 *   stderr: { onData: (data: Buffer) => void };
 *   onError: (error: Error) => void;
 *   onClose: (code: number | null) => void;
 *   getStdout: () => string;
 *   getStderr: () => string;
 * }} ProcessHandlers
 */

/**
 * @param {import('child_process').ChildProcess} child
 * @returns {boolean}
 */
function isProcessRunning(child) {
  return child.exitCode == null && child.signalCode == null;
}

/**
 * Append a chunk to the buffer, truncating to the most recent content if it
 * would exceed the maximum buffer size.
 * @param {string} current - Current buffered content
 * @param {string} chunk - New chunk to append
 * @returns {string} Bounded buffer contents
 */
function appendBounded(current, chunk) {
  const combined = current + chunk;
  return combined.length <= MAX_PROCESS_OUTPUT_BUFFER_SIZE
    ? combined
    : combined.slice(-MAX_PROCESS_OUTPUT_BUFFER_SIZE);
}

/**
 * Grace period between closing stdin and falling back to signals.
 * Gives stdin EOF time to propagate so `docker run -i --rm` containers can
 * exit on their own (and --rm can clean them up) before the Docker CLI
 * client is signalled (#1721).
 */
const STDIN_EOF_GRACE_MS = 500;

/**
 * Safely terminate a child process
 *
 * stdin is closed FIRST, then signals are used only as a fallback:
 * for `docker run -i --rm ...` MCP servers, a signal only reaches the
 * Docker *client*, not the container - the container keeps running and
 * `--rm` never fires, leaking one container per status refresh (#1721).
 * Closing stdin delivers EOF on the container's stdin, so a well-behaved
 * MCP server exits on its own and `--rm` cleans up. Signalling too early
 * would kill the client before the EOF propagates, recreating the leak,
 * hence the grace period before SIGTERM/SIGKILL.
 *
 * @param {import('child_process').ChildProcess | null} child - Child process
 * @param {string} serverName - Server name (for logging)
 */
export function safeKillProcess(child, serverName) {
  if (!child) return;
  // Already exited - nothing to clean up
  if (child.exitCode !== null || child.signalCode !== null) return;

  // Close stdin first: EOF lets docker run -i --rm containers (and any
  // child reading stdin) terminate gracefully instead of being leaked.
  try {
    if (child.stdin && !child.stdin.destroyed && !child.stdin.writableEnded) {
      child.stdin.end();
    }
  } catch (e) {
    log('debug', `Failed to close stdin for ${serverName}:`, e instanceof Error ? e.message : String(e));
  }

  // After the grace period, fall back to signals for children that do not
  // react to stdin EOF. unref() so these timers never block process exit.
  const signalTimer = setTimeout(() => terminateWithSignals(child, serverName), STDIN_EOF_GRACE_MS);
  signalTimer.unref();
}

/**
 * Signal-based termination fallback for children still alive after the
 * stdin-EOF grace period (e.g. local processes that never read stdin).
 * @param {import('child_process').ChildProcess} child - Child process
 * @param {string} serverName - Server name (for logging)
 */
function terminateWithSignals(child, serverName) {
  try {
    // Exited on its own after stdin EOF - nothing to signal
    if (child.exitCode !== null || child.signalCode !== null) return;

    if (process.platform === 'win32') {
      // Windows verification spawns may go through a cmd.exe wrapper
      // (shell:true for .cmd/.bat/npx); kill the whole tree, not just the
      // wrapper shell. taskkill /F is already forced, so no SIGKILL
      // escalation is needed on this platform.
      killChildTree(child, serverName);
      return;
    }

    if (!child.killed) {
      child.kill('SIGTERM');
      // If SIGTERM doesn't kill it, send SIGKILL after 500ms
      // Use unref() so this timer won't prevent the parent process from exiting
      const killTimer = setTimeout(() => {
        try {
          if (child.exitCode === null && child.signalCode === null && !child.killed) {
            child.kill('SIGKILL');
            log('debug', `Force killed process for ${serverName}`);
          }
        } catch (e) {
          log('debug', `SIGKILL failed for ${serverName}:`, e instanceof Error ? e.message : String(e));
        }
      }, 500);
      killTimer.unref();
      const clearKillTimer = () => clearTimeout(killTimer);
      child.once?.('exit', clearKillTimer);
      child.once?.('close', clearKillTimer);
    }
  } catch (e) {
    log('debug', `Failed to kill process for ${serverName}:`, e instanceof Error ? e.message : String(e));
  }
}

/**
 * Create process event handlers
 * @param {ProcessHandlerContext} context - Context object
 * @returns {ProcessHandlers} Collection of event handlers
 */
export function createProcessHandlers(context) {
  const { serverName, finalize } = context;
  let stdout = '';
  let stderr = '';

  return {
    stdout: {
      onData: (/** @type {Buffer} */ data) => {
        stdout = appendBounded(stdout, data.toString());
        if (hasValidMcpResponse(stdout)) {
          const serverInfo = parseServerInfo(stdout);
          finalize('connected', serverInfo);
        }
      }
    },
    stderr: {
      onData: (/** @type {Buffer} */ data) => {
        stderr = appendBounded(stderr, data.toString());
        // Log stderr output for diagnostics
        const stderrLine = data.toString().trim();
        if (stderrLine) {
          log('debug', `[${serverName}] stderr:`, stderrLine.substring(0, 200));
        }
      }
    },
    onError: (/** @type {Error} */ error) => {
      log('debug', `Process error for ${serverName}:`, error.message);
      finalize('failed', null, error.message);
    },
    onClose: (/** @type {number | null} */ code) => {
      if (hasValidMcpResponse(stdout) || stdout.includes('MCP')) {
        finalize('connected', parseServerInfo(stdout));
      } else if (code !== 0) {
        // Build a detailed error message
        let errorDetails = `Process exited with code ${code}`;
        if (stderr) {
          errorDetails += `. stderr: ${stderr.substring(0, 500)}`;
        }
        if (stdout) {
          errorDetails += `. stdout: ${stdout.substring(0, 500)}`;
        }
        finalize('failed', null, errorDetails);
      } else {
        finalize('pending', null, stderr || 'No response from server');
      }
    },
    getStdout: () => stdout,
    getStderr: () => stderr
  };
}

/**
 * Send an initialize request to the child process
 * Caller is responsible for closing stdin when appropriate.
 * @param {import('child_process').ChildProcess} child - Child process
 * @param {string} serverName - Server name
 */
export function sendInitializeRequest(child, serverName) {
  const stdin = child?.stdin;
  if (!stdin || stdin.destroyed || !stdin.writable) {
    log('debug', `Cannot write initialize request for ${serverName}: stdin is unavailable`);
    return;
  }

  let errorReported = false;
  const onStdinError = (/** @type {Error} */ error) => {
    if (errorReported) return;
    errorReported = true;
    log('debug', `Failed to write to stdin for ${serverName}:`, error.message);
  };
  stdin.once('error', onStdinError);
  try {
    stdin.write(createInitializeRequest(), (error) => {
      stdin.removeListener('error', onStdinError);
      if (error) onStdinError(error);
    });
  } catch (e) {
    stdin.removeListener('error', onStdinError);
    onStdinError(/** @type {Error} */ (e));
  }
}
