// @ts-check
/**
 * Claude Code CLI PreToolUse hook runner.
 *
 * Bridges CLI tool-permission requests to the plugin's file-IPC + frontend dialog
 * chain by reusing `canUseTool` (which writes `request-<sid>.json` and blocks,
 * polling for the `response-*.json` the Java `PermissionService` writes after the
 * user decides in the frontend `PermissionDialog`).
 *
 * Configured via a `--settings <file>` whose `hooks.PreToolUse` command points here
 * (see `ClaudeCliHookSettings`). Invoked by `claude` as a FRESH subprocess per tool
 * call — no long-lived state; all state lives in the IPC files.
 *
 * Inherits the CLI parent process env, which `ClaudeCliSession.buildCliEnvironment`
 * populates with `CLAUDE_SESSION_ID` / `CLAUDE_PERMISSION_DIR` /
 * `CLAUDE_PERMISSION_SAFETY_NET_MS` (via `CliEnvironmentBuilder.configureClaudePermissionEnv`),
 * so `permission-ipc.js` works unchanged.
 *
 * Headless `-p` mode: hooks still run, but a `permissionDecision: "ask"` ≈ denied
 * (no TTY). `canUseTool` never returns `ask` — it synchronously blocks on the GUI
 * verdict and returns only `allow`/`deny` — so we never emit `ask` here.
 */
import { canUseTool } from '../permission-handler.js';
import { debugLog } from '../permission-ipc.js';

/**
 * Read all of stdin as a UTF-8 string. Mirrors the verified pattern used by
 * `services/prompt-enhancer.js`, `services/session-title-service.js`, and
 * `services/claude/attachment-service.js` (setEncoding + string chunk cast).
 * @returns {Promise<string>}
 */
function readStdin() {
  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (/** @type {string} */ chunk) => { data += chunk; });
    process.stdin.on('end', () => resolve(data));
    process.stdin.on('error', reject);
  });
}

/**
 * Build the PreToolUse hook stdout payload for an allow verdict.
 * @param {{ behavior: 'allow' | 'deny'; updatedInput?: any; message?: string } | null} result
 * @returns {{ hookSpecificOutput: { hookEventName: string; permissionDecision: string; updatedInput?: any; [k: string]: any } }}
 */
function buildAllowPayload(result) {
  // Cast the literal so the optionally-added updatedInput field is type-allowed.
  const hookSpecificOutput = /** @type {{ hookEventName: string; permissionDecision: string; updatedInput?: any }} */ ({
    hookEventName: 'PreToolUse',
    permissionDecision: 'allow',
  });
  if (result && result.updatedInput !== undefined) {
    hookSpecificOutput.updatedInput = result.updatedInput;
  }
  return { hookSpecificOutput };
}

/**
 * Build the PreToolUse hook stdout payload for a deny verdict.
 * @param {string} reason
 * @returns {{ hookSpecificOutput: { hookEventName: string; permissionDecision: string; permissionDecisionReason: string } }}
 */
function buildDenyPayload(reason) {
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: reason,
    },
  };
}

/**
 * Write a payload to stdout (single line, no trailing newline) and exit 0.
 * @param {unknown} payload
 */
function emit(payload) {
  try {
    process.stdout.write(JSON.stringify(payload));
  } catch (e) {
    debugLog('HOOK_EMIT_ERROR', `Failed to serialize payload: ${e instanceof Error ? e.message : String(e)}`);
  }
}

/**
 * Translate a `canUseTool` result into a CLI hook decision on stdout.
 * @param {{ behavior: 'allow' | 'deny'; updatedInput?: any; message?: string } | null} result
 */
function emitDecision(result) {
  if (result && result.behavior === 'allow') {
    emit(buildAllowPayload(result));
    return;
  }
  const reason = (result && result.message) || 'Denied by plugin permission hook';
  emit(buildDenyPayload(reason));
}

async function main() {
  let payload;
  try {
    const raw = await readStdin();
    payload = raw ? JSON.parse(raw) : {};
  } catch (e) {
    debugLog('HOOK_PARSE_ERROR', `Failed to parse stdin: ${e instanceof Error ? e.message : String(e)}`);
    emit(buildDenyPayload('Permission hook: invalid stdin payload'));
    return;
  }

  const toolName = payload?.tool_name;
  const toolInput = payload?.tool_input;

  if (!toolName || typeof toolName !== 'string') {
    emit(buildDenyPayload('Permission hook: missing tool_name'));
    return;
  }

  try {
    const result = await canUseTool(toolName, toolInput);
    emitDecision(result);
  } catch (e) {
    debugLog('HOOK_CAN_USE_TOOL_ERROR', `canUseTool threw for ${toolName}: ${e instanceof Error ? e.message : String(e)}`);
    emit(buildDenyPayload(
      'Permission hook: internal error — ' + (e instanceof Error ? e.message : String(e))
    ));
  }
}

main().catch((e) => {
  // Last-resort fail-closed: never let the hook hang or crash without a verdict,
  // which would leave the CLI blocked on a tool decision.
  emit(buildDenyPayload(
    'Permission hook: fatal — ' + (e instanceof Error ? e.message : String(e))
  ));
});
