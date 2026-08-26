// @ts-check
/**
 * 进程退出策略(纯函数,便于单测;channel-manager.js 顶层 IIFE 有副作用无法直接测)。
 *
 * 三类策略(按退出时机区分):
 * - network         网络类(opencode send/abort):CLI 子进程 / HTTP keep-alive
 *                   socket 可能挂事件循环 → 短延迟强退
 * - history-readonly 只读历史类(opencode getSession/listSessions):sql.js 读本地 db,
 *                   Node 25 + Windows 下进程退出可能触发 UV_HANDLE_CLOSING assertion
 *                   (stderr 崩溃,stdout JSON 已 flush)→ 略长延迟留足 flush 时间
 * - natural         其余:setExitCode 后自然退出,确保大 JSON 输出完整 flush
 */

/**
 * 退出策略字面量联合(三类常量的值域)。
 * @typedef {'network' | 'history-readonly' | 'natural'} ExitStrategy
 */

/** @type {Readonly<{ NETWORK: ExitStrategy; HISTORY_READONLY: ExitStrategy; NATURAL: ExitStrategy }>} */
const STRATEGY_VALUES = {
  NETWORK: 'network',
  HISTORY_READONLY: 'history-readonly',
  NATURAL: 'natural',
};

/**
 * 三类退出策略常量(冻结)。值域见 {@link ExitStrategy}。
 * @satisfies {Readonly<{ NETWORK: ExitStrategy; HISTORY_READONLY: ExitStrategy; NATURAL: ExitStrategy }>}
 */
export const EXIT_STRATEGY = Object.freeze(STRATEGY_VALUES);

/** @type {Set<string>} */
const NETWORK_COMMANDS = new Set(['send', 'abort']);
/** @type {Set<string>} */
const READ_ONLY_HISTORY_COMMANDS = new Set(['getSession', 'listSessions']);
/** @type {string} */
const HISTORY_READONLY_PROVIDER = 'opencode';
/** @type {string} */
const NETWORK_PROVIDER = 'opencode';

/**
 * 按 (provider, command) 解析退出策略。
 *
 * @param {string | undefined} provider provider 名(claude/codex/opencode)
 * @param {string | undefined} command  命令名
 * @returns {ExitStrategy} 退出策略
 */
export function resolveExitStrategy(provider, command) {
  if (provider === NETWORK_PROVIDER && command !== undefined && NETWORK_COMMANDS.has(command)) {
    return EXIT_STRATEGY.NETWORK;
  }
  if (provider === HISTORY_READONLY_PROVIDER && command !== undefined && READ_ONLY_HISTORY_COMMANDS.has(command)) {
    return EXIT_STRATEGY.HISTORY_READONLY;
  }
  return EXIT_STRATEGY.NATURAL;
}

/**
 * 退出延迟(ms);history-readonly 略长以绕过 sql.js UV_HANDLE_CLOSING assert 并留足 flush。
 *
 * @param {ExitStrategy} strategy 退出策略
 * @returns {number} 延迟毫秒
 */
export function exitDelayFor(strategy) {
  return strategy === EXIT_STRATEGY.HISTORY_READONLY ? 200 : 100;
}
