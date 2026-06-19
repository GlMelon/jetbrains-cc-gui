#!/usr/bin/env node

/**
 * AI Bridge Daemon Process
 *
 * Long-running Node.js process that pre-loads the Claude SDK once and handles
 * multiple requests over stdin/stdout using NDJSON protocol.
 *
 * Protocol (stdin, one JSON per line):
 *   {"id":"1","method":"claude.send","params":{...}}
 *   {"id":"2","method":"heartbeat"}
 *
 * Protocol (stdout, one JSON per line):
 *   {"type":"daemon","event":"ready","pid":12345}
 *   {"id":"1","line":"[STREAM_START]"}
 *   {"id":"1","done":true,"success":true}
 *   {"id":"2","type":"heartbeat","ts":1234567890}
 */

import { createInterface } from 'readline';
import path from 'path';
import { execFileSync } from 'child_process';
import { getDefaultProviderRegistry } from './channels/provider-registry.js';
import { loadClaudeSdk, isClaudeSdkAvailable } from './utils/sdk-loader.js';
import {
  sendMessagePersistent,
  sendMessageWithAttachmentsPersistent,
  preconnectPersistent,
  shutdownPersistentRuntimes,
  abortCurrentTurn,
  resetRuntimePersistent,
  getContextUsagePersistent
} from './services/claude/persistent-query-service.js';
import { abortCurrentCodexTurn, resetCodexThreadCache, waitForCodexTurnCompletion } from './services/codex/message-service.js';
import { injectStartupEnvVars, isWebviewControlledEnvVar, isDangerousEnvVar } from './config/api-config.js';
import { cleanupStaleTempImages } from './services/claude/attachment-service.js';

// =============================================================================
// Startup Environment Setup (must run before any HTTPS connection)
// =============================================================================

// Sync proxy/TLS settings and AWS credentials from ~/.claude/settings.json
// BEFORE SDK preloading or any other network activity, but only for explicitly
// authorized Local settings.json / CLI Login modes. Without this, users behind
// corporate SSL-inspection proxies in those modes will get certificate
// verification errors, and Bedrock auth fails for desktop-launched IDEs.
injectStartupEnvVars();

const DAEMON_VERSION = '1.0.0';
const providerRegistry = getDefaultProviderRegistry();

let activeRequestId = null;
let activeRequestChannelId = null;
let isDaemonMode = true;
let sdkPreloaded = false;
let commandQueue = Promise.resolve();
let abortFlushPromise = null;
let queuedRequestCount = 0;
let lastAcceptedRequestSequence = 0;
let cancelAllQueuedRequestSequence = 0;
const cancelledChannelSequences = new Map();

const _originalStdoutWrite = process.stdout.write.bind(process.stdout);
const _originalStderrWrite = process.stderr.write.bind(process.stderr);
const _originalExit = process.exit;

// =============================================================================
// GUI Login Environment Fix (must run before any subprocess spawns)
// =============================================================================
//
// GUI-launched IDEs (JetBrains via WSL on Windows, Dock-launched on macOS)
// don't source the user's shell init files, so the daemon inherits a minimal
// system PATH. Probe the user's login shell once at startup and apply a
// whitelist of runtime env vars so every subprocess this daemon spawns —
// Claude's Bash tool, Codex, MCP servers, any future tool — automatically
// sees the user's full environment without per-tool Java-side patches.

if (process.platform !== 'win32' && !process.env.__AI_BRIDGE_ENV_PROBED) {
  // PATH is critical; runtime homes let tools resolve config/data dirs correctly
  const VARS_TO_INHERIT = new Set([
    'PATH',
    'NVM_DIR',
    'PYENV_ROOT',
    'RUSTUP_HOME', 'CARGO_HOME',
    'GOPATH', 'GOROOT',
    'JAVA_HOME',
    'SDKMAN_DIR', 'RBENV_ROOT',
  ]);

  const loginShell = process.env.SHELL || '/bin/bash';
  const shellBase = path.basename(loginShell);
  // fish reads config.fish by default; all other POSIX shells need -l for login profile
  const loginFlag = shellBase === 'fish' ? '-c' : '-lc';

  const tryProbeEnv = (shell, flag) => {
    try {
      return execFileSync(shell, [flag, 'env -0'], {
        timeout: 3000,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      });
    } catch {
      return null;
    }
  };

  let raw = tryProbeEnv(loginShell, loginFlag);
  let probeSource = raw ? loginShell : null;

  if (!raw && loginShell !== '/bin/bash') {
    raw = tryProbeEnv('/bin/bash', '-lc');
    if (raw) probeSource = '/bin/bash';
  }

  let applied = 0;
  if (raw) {
    for (const entry of raw.split('\0')) {
      const eqIdx = entry.indexOf('=');
      if (eqIdx < 1) continue;
      const key = entry.slice(0, eqIdx);
      if (!VARS_TO_INHERIT.has(key)) continue;
      const val = entry.slice(eqIdx + 1);
      if (key === 'PATH') {
        // Merge rather than replace: the Java launcher already enriched PATH (Homebrew,
        // nvm, ...), so adopting a login-shell PATH wholesale would drop those entries
        // whenever the shell returns a minimal one. Union (current first, append only
        // unseen entries) keeps every launcher path while still picking up dirs the
        // launcher missed (pyenv/rustup/sdkman). This also fixes Apple-Silicon Homebrew
        // PATHs, which the old "$HOME must appear" guard wrongly rejected.
        const current = process.env.PATH || '';
        const seen = new Set(current.split(path.delimiter).filter(Boolean));
        const additions = val.split(path.delimiter).filter((p) => p && !seen.has(p));
        if (additions.length > 0) {
          process.env.PATH = current
            ? `${current}${path.delimiter}${additions.join(path.delimiter)}`
            : val;
          applied++;
        }
        continue;
      }
      if (val !== process.env[key]) {
        process.env[key] = val;
        applied++;
      }
    }
  }

  process.env.__AI_BRIDGE_ENV_PROBED = '1';
  _originalStderrWrite(
    `[daemon] env probe: shell=${probeSource ?? 'none'} vars-applied=${applied}\n`,
    'utf8',
  );
}

// One-shot diagnostic: confirms WSLENV-propagated vars actually reached the daemon.
// If CLAUDE_PERMISSION_DIR shows up as `unset` here while Java logs claim to have
// set it, WSLENV is not being honored and the permission bridge will hang.
_originalStderrWrite(
  `[daemon] bridge env: CLAUDE_PERMISSION_DIR=${process.env.CLAUDE_PERMISSION_DIR ?? 'unset'}`
  + ` CLAUDE_SESSION_ID=${process.env.CLAUDE_SESSION_ID ?? 'unset'}`
  + ` WSLENV=${process.env.WSLENV ?? 'unset'}\n`,
  'utf8',
);

function writeRawLine(obj) {
  _originalStdoutWrite(JSON.stringify(obj) + '\n', 'utf8');
}

function sendDaemonEvent(event, data = {}) {
  writeRawLine({ type: 'daemon', event, ...data });
}

function sendQueueWaitingEvent(requestId, aheadCount) {
  sendDaemonEvent('queue_waiting', {
    requestId,
    aheadCount,
  });
}

function sendQueueStartedEvent(requestId) {
  sendDaemonEvent('queue_started', {
    requestId,
  });
}

function sendQueueClearedEvent(requestId) {
  sendDaemonEvent('queue_cleared', {
    requestId,
  });
}

function normalizeChannelId(channelId) {
  return typeof channelId === 'string' && channelId.trim() ? channelId.trim() : null;
}

function getRequestSequence(request) {
  const parsed = Number.parseInt(request?.id, 10);
  return Number.isFinite(parsed) ? parsed : 0;
}

function markQueuedRequestsCancelled(request) {
  const requestSequence = getRequestSequence(request) || lastAcceptedRequestSequence;
  const channelId = normalizeChannelId(request?.channelId || request?.params?.channelId);
  if (channelId) {
    const current = cancelledChannelSequences.get(channelId) || 0;
    cancelledChannelSequences.set(channelId, Math.max(current, requestSequence));
  } else {
    cancelAllQueuedRequestSequence = Math.max(cancelAllQueuedRequestSequence, requestSequence);
  }
}

function isQueuedRequestCancelled(request) {
  const requestSequence = getRequestSequence(request);
  if (requestSequence > 0 && requestSequence <= cancelAllQueuedRequestSequence) {
    return true;
  }

  const channelId = normalizeChannelId(request?.params?.channelId);
  if (!channelId) {
    return false;
  }
  const cancelledThrough = cancelledChannelSequences.get(channelId) || 0;
  return requestSequence > 0 && requestSequence <= cancelledThrough;
}

function getCurrentRequestId() {
  return activeRequestId;
}

function shouldAbortActiveRequest(request) {
  const targetChannelId = normalizeChannelId(request?.channelId || request?.params?.channelId);
  return !targetChannelId || targetChannelId === activeRequestChannelId;
}

process.stdout.write = function (chunk, encoding, callback) {
  const text = typeof chunk === 'string' ? chunk : chunk.toString(encoding || 'utf8');
  const requestId = getCurrentRequestId();

  if (requestId) {
    const lines = text.split('\n');
    for (const line of lines) {
      if (line.length > 0) {
        writeRawLine({ id: requestId, line });
      }
    }
    if (typeof callback === 'function') callback();
    return true;
  }

  const trimmed = text.trim();
  if (trimmed.startsWith('{')) {
    return _originalStdoutWrite(chunk, encoding, callback);
  }

  if (trimmed.length > 0) {
    const lines = text.split('\n');
    for (const line of lines) {
      if (line.trim().length > 0) {
        writeRawLine({ type: 'daemon', event: 'log', message: line });
      }
    }
  }

  if (typeof callback === 'function') callback();
  return true;
};

// Expose the pre-interception writer so out-of-band emitters can write
// process-level NDJSON that must NOT be wrapped with activeRequestId.
// The per-runtime perpetual reader (runtime-lifecycle.js) uses this to emit
// inter-turn 'session_updated' events; without it those events would be
// misrouted to whatever request happens to be active. See startPerpetualReader().
process.stdout._originalStdoutWrite = _originalStdoutWrite;

/**
 * Override console.log to go through our tagged stdout.
 */
console.log = function (...args) {
  const text = args
    .map((a) => (typeof a === 'string' ? a : JSON.stringify(a)))
    .join(' ');
  process.stdout.write(text + '\n');
};

console.error = function (...args) {
  const text = args
    .map((a) => (typeof a === 'string' ? a : JSON.stringify(a)))
    .join(' ');
  const requestId = getCurrentRequestId();
  if (requestId) {
    writeRawLine({ id: requestId, stderr: text });
  } else {
    _originalStderrWrite(text + '\n', 'utf8');
  }
};

process.exit = function (code) {
  if (isDaemonMode) {
    const capturedId = getCurrentRequestId();
    activeRequestId = null;
    activeRequestChannelId = null;

    if (capturedId) {
      if (code === 0) {
        writeRawLine({ id: capturedId, done: true, success: true });
      } else {
        writeRawLine({
          id: capturedId,
          done: true,
          success: false,
          error: `process.exit(${code}) intercepted by daemon`,
        });
      }
    }

    throw new Error(`[daemon] process.exit(${code}) intercepted`);
  }
  _originalExit(code);
};

try {
  const exitCodeDescriptor = Object.getOwnPropertyDescriptor(process, 'exitCode');
  if (exitCodeDescriptor?.configurable) {
    let _exitCode = process.exitCode || 0;
    Object.defineProperty(process, 'exitCode', {
      set(code) {
        if (!isDaemonMode) {
          _exitCode = code;
        }
      },
      get() {
        return _exitCode;
      },
      configurable: true,
    });
  }
} catch (error) {
  _originalStderrWrite(`[daemon] Unable to patch process.exitCode: ${error.message}\n`, 'utf8');
}

async function preloadSdks() {
  try {
    if (isClaudeSdkAvailable()) {
      sendDaemonEvent('sdk_loading', { provider: 'claude' });
      await loadClaudeSdk();
      sdkPreloaded = true;
      sendDaemonEvent('sdk_loaded', { provider: 'claude' });
    } else {
      sendDaemonEvent('sdk_unavailable', { provider: 'claude' });
    }
  } catch (e) {
    sendDaemonEvent('sdk_load_error', {
      provider: 'claude',
      error: e.message,
    });
  }
}

async function dispatchProviderCommand(method, params) {
  const dotIndex = method.indexOf('.');
  if (dotIndex < 0) {
    throw new Error(`Invalid method format: ${method}. Expected "provider.command"`);
  }

  const provider = method.substring(0, dotIndex);
  const command = method.substring(dotIndex + 1);
  const stdinData = { ...params };
  delete stdinData.env;

  if (provider === 'claude' && command === 'send') {
    await sendMessagePersistent(stdinData);
    return;
  }
  if (provider === 'claude' && command === 'sendWithAttachments') {
    await sendMessageWithAttachmentsPersistent(stdinData);
    return;
  }
  if (provider === 'claude' && command === 'preconnect') {
    await preconnectPersistent(stdinData);
    return;
  }
  if (provider === 'claude' && command === 'resetRuntime') {
    await resetRuntimePersistent(stdinData);
    return;
  }
  if (provider === 'claude' && command === 'getContextUsage') {
    await getContextUsagePersistent(stdinData);
    return;
  }

  await providerRegistry.dispatch(provider, command, [], stdinData);
}

async function processRequest(request) {
  const { id, method, params = {} } = request;

  if (method === 'heartbeat') {
    writeRawLine({
      id: id || '0',
      type: 'heartbeat',
      ts: Date.now(),
      sdkPreloaded,
      memoryUsage: process.memoryUsage().heapUsed,
    });
    return;
  }

  if (method === 'status') {
    writeRawLine({
      id,
      type: 'status',
      version: DAEMON_VERSION,
      pid: process.pid,
      uptime: process.uptime(),
      sdkPreloaded,
      memoryUsage: process.memoryUsage(),
    });
    return;
  }

  if (method === 'shutdown') {
    // Acknowledge the request first, then run the unified cleanup path so child
    // processes are torn down in the correct order (see gracefulShutdown).
    writeRawLine({ id: id || '0', done: true, success: true });
    await gracefulShutdown('requested');
    return;
  }

  if (!id) {
    _originalStderrWrite(`[daemon] Ignoring request without id: ${method}\n`, 'utf8');
    return;
  }

  sendQueueStartedEvent(id);
  activeRequestId = id;
  activeRequestChannelId = normalizeChannelId(params?.channelId);
  const savedEnv = {};

  try {
    if (params.env && typeof params.env === 'object') {
      for (const [key, value] of Object.entries(params.env)) {
        // Request env can include settings.json values. Do not let stale
        // environment controls override the webview's per-turn model, context,
        // or reasoning selections.
        if (isWebviewControlledEnvVar(key)) {
          continue;
        }
        // Security (C): never let request/settings.json env inject code-execution or
        // library-injection variables (NODE_OPTIONS, LD_PRELOAD, DYLD_*, …). A malicious
        // project's .claude/settings.json env block would otherwise run arbitrary code in
        // the daemon or any child process the SDK spawns.
        if (isDangerousEnvVar(key)) {
          console.warn(`[SECURITY] Ignoring dangerous env var from request: ${key}`);
          continue;
        }
        if (value !== undefined && value !== null) {
          savedEnv[key] = process.env[key];
          process.env[key] = String(value);
        }
      }
    }

    await dispatchProviderCommand(method, params);
    writeRawLine({ id, done: true, success: true });
  } catch (error) {
    if (activeRequestId !== null) {
      writeRawLine({
        id,
        done: true,
        success: false,
        error: error.message || String(error),
        code: error.code,
      });
    }
  } finally {
    sendQueueClearedEvent(id);
    activeRequestId = null;
    activeRequestChannelId = null;
    for (const [key, originalValue] of Object.entries(savedEnv)) {
      if (originalValue === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = originalValue;
      }
    }
  }
}

/**
 * Unified, idempotent graceful shutdown. Centralises the cleanup that every
 * termination path must perform, so a child process (Claude persistent query /
 * Codex transport) is never leaked regardless of HOW the daemon is told to stop:
 *
 *   - explicit `shutdown` command from the Java parent
 *   - stdin close (parent closed the pipe / parent exiting)
 *   - parent-process disappearance (IDEA crash / force-kill)
 *   - POSIX signals (SIGINT / SIGTERM / SIGHUP) and Windows SIGBREAK
 *
 * Ordering matters: the in-flight Codex turn must be aborted and awaited BEFORE
 * the cached transport is reset, otherwise resetCodexThreadCache() drops a thread
 * that the SDK is still streaming through, leaking the child node process behind it.
 */
let shuttingDown = false;
async function gracefulShutdown(reason) {
  if (shuttingDown) return;
  shuttingDown = true;

  const forceExitTimer = setTimeout(() => {
    _originalStderrWrite(`[daemon] Shutdown timeout (5s, reason=${reason}), forcing exit\n`, 'utf8');
    _originalExit(0);
  }, 5000);
  forceExitTimer.unref();

  // Stop accepting new requests immediately.
  isDaemonMode = false;

  try {
    // 1. Close the persistent Claude query (terminates the long-lived Claude process).
    await shutdownPersistentRuntimes();
  } catch (e) {
    _originalStderrWrite(`[daemon] Failed to shutdown persistent runtimes: ${e.message}\n`, 'utf8');
  }

  try {
    // 2. Abort any in-flight Codex turn and wait for it to fully unwind BEFORE
    //    clearing the cached transport. Resetting mid-stream leaks the child node
    //    process backing the Codex SDK; awaiting prevents that.
    abortCurrentCodexTurn();
    await waitForCodexTurnCompletion();
  } catch (e) {
    _originalStderrWrite(`[daemon] Failed to abort codex turn: ${e.message}\n`, 'utf8');
  }

  try {
    // 3. Drop cached Codex thread/transport state.
    resetCodexThreadCache();
  } catch (e) {
    _originalStderrWrite(`[daemon] Failed to reset codex thread cache: ${e.message}\n`, 'utf8');
  }

  clearTimeout(forceExitTimer);

  try {
    sendDaemonEvent('shutdown', { reason });
  } catch (_) {
    // stdout may already be torn down during force-close; non-fatal.
  }

  _originalExit(0);
}

(async () => {
  process.on('uncaughtException', (error) => {
    _originalStderrWrite(
      `[daemon] Uncaught exception: ${error.message}\n${error.stack}\n`,
      'utf8'
    );
    const requestId = getCurrentRequestId();
    if (requestId) {
      writeRawLine({
        id: requestId,
        done: true,
        success: false,
        error: `Uncaught exception: ${error.message}`,
      });
      activeRequestId = null;
      activeRequestChannelId = null;
    }
    // An uncaught exception leaves daemon invariants broken; continuing risks
    // leaking the Claude/Codex child processes and corrupting the SDK stream
    // state. Tear everything down via the unified path instead of soldiering on.
    // Not awaited (handler is sync); gracefulShutdown has a 5s force-exit backstop.
    gracefulShutdown('uncaught_exception');
  });

  process.on('unhandledRejection', (reason) => {
    _originalStderrWrite(
      `[daemon] Unhandled rejection: ${reason}\n`,
      'utf8'
    );
    const requestId = getCurrentRequestId();
    if (requestId) {
      writeRawLine({
        id: requestId,
        done: true,
        success: false,
        error: `Unhandled rejection: ${String(reason)}`,
      });
      activeRequestId = null;
      activeRequestChannelId = null;
    }
  });

  sendDaemonEvent('starting', {
    pid: process.pid,
    version: DAEMON_VERSION,
    nodeVersion: process.version,
    platform: process.platform,
  });

  await preloadSdks();
  cleanupStaleTempImages().catch(() => {});

  sendDaemonEvent('ready', {
    pid: process.pid,
    sdkPreloaded,
  });

  const rl = createInterface({
    input: process.stdin,
    crlfDelay: Infinity,
  });

  rl.on('line', (line) => {
    if (!line.trim()) return;

    let request;
    try {
      request = JSON.parse(line);
    } catch (e) {
      _originalStderrWrite(
        `[daemon] Invalid JSON input: ${line.substring(0, 200)}\n`,
        'utf8'
      );
      return;
    }

    if (request.method === 'heartbeat' || request.method === 'status') {
      processRequest(request);
      return;
    }

    if (request.method === 'abort') {
      markQueuedRequestsCancelled(request);
      _originalStderrWrite(
        `[daemon] Abort requested, active request: ${activeRequestId || 'none'}\n`,
        'utf8'
      );
      if (shouldAbortActiveRequest(request)) {
        // Fire abort signals immediately, then set a flush promise so the
        // command queue waits for the abort to fully propagate before
        // starting the next request.  This prevents a new request from
        // being queued behind a still-unwinding abort (the root cause of
        // the "permanently stuck in queue" bug with Codex).
        abortCurrentTurn();
        abortCurrentCodexTurn();
        abortFlushPromise = Promise.allSettled([
          // Claude: abortCurrentTurn already awaits disposal
          Promise.resolve(),
          // Codex: wait for the SDK stream to fully unwind
          waitForCodexTurnCompletion(),
        ]).then((results) => {
          for (const result of results) {
            if (result.status === 'rejected') {
              _originalStderrWrite(
                `[daemon] Abort flush error: ${result.reason?.message || result.reason}\n`,
                'utf8'
              );
            }
          }
          abortFlushPromise = null;
        });
      }
      writeRawLine({ id: request.id || '0', done: true, success: true });
      return;
    }

    queuedRequestCount += 1;
    lastAcceptedRequestSequence = Math.max(lastAcceptedRequestSequence, getRequestSequence(request));
    const aheadCount = activeRequestId ? queuedRequestCount - 1 : queuedRequestCount;
    if (aheadCount > 0) {
      sendQueueWaitingEvent(request.id, aheadCount);
    }

    commandQueue = commandQueue
      .then(async () => {
        // Wait for any in-flight abort to fully propagate before starting
        // the next request.  Without this, a new Codex request would be
        // queued behind a still-unwinding SDK stream and show "排队中".
        if (abortFlushPromise) {
          await abortFlushPromise;
        }
        if (isQueuedRequestCancelled(request)) {
          sendQueueClearedEvent(request.id);
          writeRawLine({
            id: request.id,
            done: true,
            success: false,
            aborted: true,
          });
          return;
        }
        return processRequest(request);
      })
      .catch((e) => {
        _originalStderrWrite(
          `[daemon] Request queue error: ${e.message}\n`,
          'utf8'
        );
      })
      .finally(() => {
        queuedRequestCount = Math.max(0, queuedRequestCount - 1);
      });
  });

  rl.on('close', async () => {
    await gracefulShutdown('stdin_closed');
  });

  // --- Signal handlers ---
  // If the daemon is explicitly signalled (kill, terminal hangup, Windows
  // Ctrl-Break), clean up child processes instead of dying abruptly and
  // orphaning the Claude/Codex SDK processes. SIGBREAK only exists on Windows;
  // the try/catch guards registration so platforms lacking a given signal are
  // skipped instead of throwing ERR_UNKNOWN_SIGNAL.
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP', 'SIGBREAK']) {
    try {
      process.on(sig, () => {
        _originalStderrWrite(`[daemon] Received ${sig}, shutting down\n`, 'utf8');
        gracefulShutdown(sig);
      });
    } catch (_) {
      // Signal not supported on this platform; skip.
    }
  }

  // --- Parent process monitoring ---
  // Periodically verify the Java parent is still alive. When IDEA crashes or is
  // force-killed, stdin may not close cleanly, leaving orphan daemon processes.
  // On Unix, process.ppid changes to 1 (init/launchd) when the parent dies.
  //
  // L11 fix: poll every 1.5s instead of the legacy 10s (then 3s). The previous
  // windows let orphan daemons linger after a hard IDE crash before noticing
  // their parent was gone. 1.5s tightens the worst-case orphan duration further
  // while staying well above the setInterval precision floor. The check is a
  // cheap kill(pid, 0) syscall + a comparison, so the increased polling rate is
  // negligible overhead.
  const PPID_CHECK_INTERVAL_MS = 1500;
  const initialPpid = process.ppid;
  const ppidMonitor = setInterval(() => {
    const currentPpid = process.ppid;
    const reparented = currentPpid !== initialPpid && currentPpid === 1;
    let parentGone = false;

    if (!reparented && currentPpid !== 1) {
      try {
        process.kill(currentPpid, 0);
      } catch (err) {
        if (err.code === 'ESRCH') {
          parentGone = true;
        }
      }
    }

    if (reparented || parentGone) {
      _originalStderrWrite(
        `[daemon] Parent process (ppid=${initialPpid}) is gone (current ppid=${currentPpid}), exiting\n`,
        'utf8'
      );
      // Run the unified cleanup so child SDK processes are killed, not orphaned.
      // Not awaited: the interval callback is sync; gracefulShutdown has its own
      // force-exit backstop.
      gracefulShutdown('parent_gone');
    }
  }, PPID_CHECK_INTERVAL_MS);
  ppidMonitor.unref();
})();
