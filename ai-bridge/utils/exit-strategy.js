/**
 * 进程退出策略(纯函数,便于单测;channel-manager.js 顶层 IIFE 有副作用无法直接测)。
 *
 * 四类策略(按退出时机区分):
 * - network         网络类(opencode send/abort):@opencode-ai/sdk + undici keep-alive
 *                   socket 可能挂事件循环 → 短延迟强退
 * - rewind          rewindFiles(任意 provider):恢复 SDK 会话,MCP 连接可能保持开启 → 短延迟强退
 * - history-readonly 只读历史类(opencode getSession/listSessions):sql.js 读本地 db,
 *                   Node 25 + Windows 下进程退出可能触发 UV_HANDLE_CLOSING assertion
 *                   (stderr 崩溃,stdout JSON 已 flush)→ 略长延迟留足 flush 时间
 * - natural         其余:setExitCode 后自然退出,确保大 JSON 输出完整 flush
 */
export const EXIT_STRATEGY = Object.freeze({
  NETWORK: 'network',
  REWIND: 'rewind',
  HISTORY_READONLY: 'history-readonly',
  NATURAL: 'natural',
});

const NETWORK_COMMANDS = new Set(['send', 'abort']);
const READ_ONLY_HISTORY_COMMANDS = new Set(['getSession', 'listSessions']);
const HISTORY_READONLY_PROVIDER = 'opencode';
const NETWORK_PROVIDER = 'opencode';

export function resolveExitStrategy(provider, command) {
  if (command === 'rewindFiles') {
    return EXIT_STRATEGY.REWIND;
  }
  if (provider === NETWORK_PROVIDER && NETWORK_COMMANDS.has(command)) {
    return EXIT_STRATEGY.NETWORK;
  }
  if (provider === HISTORY_READONLY_PROVIDER && READ_ONLY_HISTORY_COMMANDS.has(command)) {
    return EXIT_STRATEGY.HISTORY_READONLY;
  }
  return EXIT_STRATEGY.NATURAL;
}

/** 退出延迟(ms);history-readonly 略长以绕过 sql.js UV_HANDLE_CLOSING assert 并留足 flush。 */
export function exitDelayFor(strategy) {
  return strategy === EXIT_STRATEGY.HISTORY_READONLY ? 200 : 100;
}
