#!/usr/bin/env node
// @ts-check

/**
 * AI Bridge Channel Manager
 * Unified bridge entry point for Claude/Codex SDKs and CLI providers
 *
 * Command format:
 *   node channel-manager.js <provider> <command> [args...]
 *
 * Provider:
 *   claude   - Claude Agent SDK (@anthropic-ai/claude-agent-sdk)
 *   codex    - Codex SDK (@openai/codex-sdk)
 *   grok     - Grok CLI (no SDK; spawns local `grok` binary)
 *   kimi     - Kimi CLI (no SDK; spawns local `kimi` binary)
 *   opencode - OpenCode CLI (no SDK; spawns local `opencode` binary)
 *   pi       - PI CLI (no SDK; spawns local `pi` binary)
 *   omp      - OMP CLI (no SDK; spawns local `omp` binary)
 *   dsh      - DeepSeek Harness (Host RPC + WS mux against local `dsh web`)
 *
 * Commands:
 *   send                - Send a message (parameters passed via stdin as JSON)
 *   sendWithAttachments - Send a message with attachments (claude only)
 *   getSession          - Retrieve session message history (claude/opencode)
 *
 * Design notes:
 * - Single entry point that dispatches to different services based on the provider parameter
 * - sessionId/threadId is managed by the caller (Java side)
 * - Messages and other parameters are passed via stdin in JSON format
 */

// Shared utilities
import { readStdinData } from './utils/stdin-utils.js';
import { getDefaultProviderRegistry } from './channels/provider-registry.js';
import { injectStartupEnvVars, configureCliIdentity } from './config/api-config.js';
import { resolveExitStrategy, exitDelayFor, EXIT_STRATEGY } from './utils/exit-strategy.js';

/**
 * Write a JSON payload to stdout and exit once the bytes are flushed.
 *
 * `console.log` followed by `process.exit` races the stdout buffer: for a
 * piped stdout the underlying `process.stdout.write` is asynchronous, and
 * `process.exit` does not wait for it to drain, truncating the JSON. Writing
 * explicitly and exiting in the flush callback guarantees the payload reaches
 * the OS pipe first. The timeout fallback ensures the process still terminates
 * if the callback never fires (e.g. a broken pipe).
 */
function writeJsonAndExit(payload, code = 0) {
  let exited = false;
  const exitNow = () => {
    if (!exited) {
      exited = true;
      process.exit(code);
    }
  };
  process.stdout.write(JSON.stringify(payload) + '\n', 'utf8', exitNow);
  setTimeout(exitNow, 5000);
}

// Sync proxy/TLS settings and AWS credentials from ~/.claude/settings.json
// BEFORE any network activity, but only for explicitly authorized Local
// settings.json / CLI Login modes. Without this, users behind corporate
// SSL-inspection proxies in those modes will get certificate verification
// errors, and Bedrock auth fails for desktop-launched IDEs.
injectStartupEnvVars();

// Configure CLI client identity before any SDK loading
configureCliIdentity();

// Diagnostic logging: startup info
console.error('[DIAG-ENTRY] ========== CHANNEL-MANAGER STARTUP ==========');
console.error('[DIAG-ENTRY] Node.js version:', process.version);
console.error('[DIAG-ENTRY] Platform:', process.platform);
console.error('[DIAG-ENTRY] CWD:', process.cwd());
console.error('[DIAG-ENTRY] argv:', process.argv);

// Parse command-line arguments
const provider = process.argv[2];
const command = process.argv[3];
const args = process.argv.slice(4);

// Diagnostic logging: argument info
console.error('[DIAG-ENTRY] Provider:', provider);
console.error('[DIAG-ENTRY] Command:', command);
console.error('[DIAG-ENTRY] Args:', args);

// Error handling
process.on('uncaughtException', (error) => {
  console.error('[UNCAUGHT_ERROR]', error.message);
  writeJsonAndExit({
    success: false,
    error: error.message
  }, 1);
});

process.on('unhandledRejection', (reason) => {
  console.error('[UNHANDLED_REJECTION]', reason);
  writeJsonAndExit({
    success: false,
    error: String(reason)
  }, 1);
});

const providerRegistry = getDefaultProviderRegistry();

// Execute command
(async () => {
  console.error('[DIAG-EXEC] ========== STARTING EXECUTION ==========');
  try {
    // Validate provider
    console.error('[DIAG-EXEC] Validating provider...');
    if (!provider || !providerRegistry.has(provider)) {
      console.error('Invalid provider. Use "claude", "codex", "grok", "kimi", "opencode", "pi", "omp", or "dsh"');
      writeJsonAndExit({
        success: false,
        error: 'Invalid provider: ' + provider
      }, 1);
      return;
    }

    // Validate command
    if (!command) {
      console.error('No command specified');
      writeJsonAndExit({
        success: false,
        error: 'No command specified'
      }, 1);
      return;
    }

    // Read stdin data
    console.error('[DIAG-EXEC] Reading stdin data...');
    const stdinData = await readStdinData(provider);
    console.error('[DIAG-EXEC] Stdin data received, keys:', stdinData ? Object.keys(stdinData) : 'null');

    // Dispatch to the appropriate provider handler
    console.error('[DIAG-EXEC] Dispatching to handler:', provider);
    await providerRegistry.dispatch(provider, command, args, stdinData);
    console.error('[DIAG-EXEC] Handler completed successfully');

    // IMPORTANT: Do not use process.exit(0) for natural-exit commands -- it terminates the
    // process before the stdout buffer is fully flushed, which can truncate large JSON output
    // (e.g., the history returned by getSession). Set process.exitCode and let the process exit
    // naturally so all I/O completes. 退出策略判定集中在 resolveExitStrategy(单测覆盖)。
    process.exitCode = 0;
    const exitStrategy = resolveExitStrategy(provider, command);
    if (exitStrategy !== EXIT_STRATEGY.NATURAL) {
      // network / rewind / history-readonly:各自的句柄(SDK fetch socket / MCP 连接 /
      // sql.js db)可能阻止自然退出,按策略延迟强退(history-readonly 200ms 绕过 Node 25 +
      // Windows 的 sql.js UV_HANDLE_CLOSING assert,其余 100ms),留足 stdout flush 时间。
      setTimeout(() => process.exit(0), exitDelayFor(exitStrategy));
    }

  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[COMMAND_ERROR]', message);
    writeJsonAndExit({
      success: false,
      error: message
    }, 1);
  }
})();
