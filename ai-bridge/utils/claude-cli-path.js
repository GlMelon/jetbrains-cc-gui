// @ts-check
/**
 * Resolves the user-configured Claude Code CLI executable, if any.
 *
 * The Java side sets CLAUDE_CODE_PATH when the user has provided a custom
 * path in Settings > Basic. When set, the bridge spawns that binary instead
 * of the `claude` executable resolved from PATH.
 *
 * Returns null when unset/blank so callers can spread the field conditionally.
 *
 * @returns {string | null} Custom CLI path, or null when unset/blank.
 */
export function getClaudeCliPathOverride() {
  const raw = process.env.CLAUDE_CODE_PATH;
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : null;
}
