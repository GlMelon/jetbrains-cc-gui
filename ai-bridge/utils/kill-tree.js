// @ts-check
/**
 * Shared process-tree kill helper for CLI / MCP child processes.
 *
 * Background: on Windows CLIs are spawned through a cmd.exe wrapper
 * (see utils/cli-path.js), so child.kill() only terminates the wrapper shell
 * and the real CLI process stays behind as an orphan. taskkill /F /T kills the
 * whole tree. On Unix children are spawned detached (new process group), so a
 * process-group SIGTERM reaches the whole tree.
 *
 * Extracted from services/claude/message-sender.js so every kill path shares
 * one implementation.
 */

import { spawnSync } from 'child_process';

/**
 * Kill a child process and its entire process tree.
 *
 * - Windows: `taskkill /F /T /PID <pid>` force-kills the tree (including a
 *   cmd.exe wrapper's children); falls back to child.kill() when no pid is
 *   available or taskkill itself fails to launch.
 * - Unix: SIGTERM to the child's process group (requires detached spawn);
 *   falls back to signaling the single process.
 *
 * The guard is exit-based (not child.killed): Node sets killed=true after the
 * first kill() call even while the process is still alive, so it cannot be
 * trusted to skip redundant kills.
 *
 * @param {import('child_process').ChildProcess | null | undefined} child
 * @param {string} [label] optional log label; kill failures are logged when set
 * @returns {void}
 */
export function killChildTree(child, label) {
  if (!child || child.exitCode != null || child.signalCode != null) return;
  try {
    if (process.platform === 'win32') {
      const pid = child.pid;
      if (pid) {
        try {
          spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], {
            stdio: 'ignore',
            timeout: 5000,
          });
          return;
        } catch (_) {
          // taskkill failed to launch — fall through to the direct kill
        }
      }
      child.kill('SIGTERM');
    } else {
      try {
        process.kill(-child.pid, 'SIGTERM');
      } catch {
        child.kill('SIGTERM');
      }
    }
  } catch (error) {
    if (label) {
      console.error(`[WARN][${label}] Failed to kill child tree:`, error?.message || error);
    }
  }
}
