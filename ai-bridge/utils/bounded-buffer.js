// @ts-check
/**
 * Bounded rolling-tail buffer helpers for child-process stream diagnostics.
 *
 * Chatty child stderr (npx download logs, warning spam) must be drained to
 * avoid pipe-buffer deadlock, but never accumulated unboundedly: keep only the
 * most recent characters, which are the useful diagnostics at failure time.
 * Pattern mirrors services/claude/mcp-status/process-manager.js appendBounded
 * and utils/cli-spawn.js stderrTail, unified here for reuse (总则四).
 */

/**
 * Append chunk to a bounded rolling tail buffer, keeping only the most recent
 * `maxChars` characters when the combined content exceeds the bound.
 * @param {string} current - Current buffered content
 * @param {string} chunk - New chunk to append
 * @param {number} maxChars - Maximum retained characters
 * @returns {string} Bounded buffer contents
 */
export function appendBounded(current, chunk, maxChars) {
  const combined = current + chunk;
  return combined.length <= maxChars ? combined : combined.slice(-maxChars);
}
