import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveExitStrategy } from '../../utils/exit-strategy.js';

test('resolveExitStrategy: opencode 网络命令(send/abort)归类为 network 强退', () => {
  assert.equal(resolveExitStrategy('opencode', 'send'), 'network');
  assert.equal(resolveExitStrategy('opencode', 'abort'), 'network');
});

test('resolveExitStrategy: rewindFiles 不区分 provider 归类为 rewind 强退', () => {
  assert.equal(resolveExitStrategy('claude', 'rewindFiles'), 'rewind');
  assert.equal(resolveExitStrategy('codex', 'rewindFiles'), 'rewind');
  assert.equal(resolveExitStrategy('opencode', 'rewindFiles'), 'rewind');
});

test('resolveExitStrategy: opencode 只读历史命令(getSession/listSessions)归类为 history-readonly', () => {
  assert.equal(resolveExitStrategy('opencode', 'getSession'), 'history-readonly');
  assert.equal(resolveExitStrategy('opencode', 'listSessions'), 'history-readonly');
});

test('resolveExitStrategy: 其余组合自然退出(claude getSession / claude send / opencode getMcpServerTools / codex abort)', () => {
  assert.equal(resolveExitStrategy('claude', 'getSession'), 'natural');
  assert.equal(resolveExitStrategy('claude', 'send'), 'natural');
  assert.equal(resolveExitStrategy('opencode', 'getMcpServerTools'), 'natural');
  assert.equal(resolveExitStrategy('codex', 'abort'), 'natural');
});

test('resolveExitStrategy: 缺参防御性归 natural', () => {
  assert.equal(resolveExitStrategy(undefined, undefined), 'natural');
});
