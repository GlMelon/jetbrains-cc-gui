// @vitest-environment happy-dom

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useLocalSlashCommands } from '../../src/hooks/useLocalSlashCommands';
import { bridgeHub } from '../../src/bridge/hub';
import { DOWNSTREAM } from '../../src/generated/protocol';
import {
  setupSlashCommandsCallback,
  resetSlashCommandsState,
} from '../../src/components/ChatInputBox/providers/slashCommandProvider';
import { __setModelRegistryForTests } from '../../src/utils/modelRegistry';

/**
 * Tests for plugin-local slash commands.
 *
 * The backend (SlashCommandRegistry) is the SSOT for which commands are local:
 * it annotates the command payload with `localAction`. These tests seed the
 * command cache through the same slash.commands downstream event the backend
 * dispatches, then assert the hook executes the matching UI action.
 */
describe('useLocalSlashCommands', () => {
  const t = ((key: string, opts?: any) => opts?.defaultValue ?? key) as any;
  const parseBridgeCall = (call: string) => JSON.parse(call) as { type: string; content: string };

  // Mirrors what the backend delivers for the Claude provider.
  const CLAUDE_COMMANDS = [
    { name: '/clear', description: 'Clear conversation and start a new session', source: 'builtin', localAction: 'new_session' },
    { name: '/resume', description: 'Resume a previous conversation', source: 'builtin', localAction: 'open_history' },
    { name: '/plan', description: 'Switch to plan mode', source: 'builtin', localAction: 'plan_mode' },
    { name: '/context', description: 'Visualize current context usage', source: 'builtin', localAction: 'context_usage' },
    { name: '/model', description: 'Select a model', source: 'builtin', localAction: 'model_picker' },
    { name: '/help', description: 'Show available commands', source: 'builtin', localAction: 'help' },
    { name: '/compact', description: 'Summarize conversation to free context', source: 'builtin' },
  ];

  // Mirrors what the backend delivers for Codex: /plan and /context are NOT local there.
  const CODEX_COMMANDS = [
    { name: '/clear', description: 'Clear conversation and start a new session', source: 'builtin', localAction: 'new_session' },
    { name: '/model', description: 'Select a model', source: 'builtin', localAction: 'model_picker' },
    { name: '/help', description: 'Show available commands', source: 'builtin', localAction: 'help' },
    { name: '/plan', description: 'Switch to plan mode', source: 'builtin' },
    { name: '/compact', description: 'Summarize conversation to free tokens', source: 'builtin' },
  ];

  const seedCommands = (commands: Array<Record<string, unknown>>) => {
    setupSlashCommandsCallback();
    bridgeHub.dispatch(DOWNSTREAM.SLASH_COMMANDS, JSON.stringify(commands));
  };

  const createOptions = (overrides: Record<string, unknown> = {}) => ({
    t,
    addToast: vi.fn(),
    selectedModel: 'claude-opus-4-8',
    chatInputRef: { current: { openModelSelect: vi.fn() } },
    setMessages: vi.fn(),
    setCurrentView: vi.fn(),
    forceCreateNewSession: vi.fn(),
    handleModeSelect: vi.fn(),
    longContextEnabled: false,
    openContextUsageDialog: vi.fn(),
    closeContextUsageDialog: vi.fn().mockReturnValue(true),
    ...overrides,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any;

  const run = async (opts: any, text: string): Promise<boolean> => {
    const { result } = renderHook(() => useLocalSlashCommands(opts));
    let handled = false;
    await act(async () => {
      handled = await result.current.tryExecuteLocalCommand(text);
    });
    return handled;
  };

  beforeEach(() => {
    window.sendToJava = vi.fn();
    resetSlashCommandsState();
    seedCommands(CLAUDE_COMMANDS);
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5-pro',
          identifier: 'mimo-v2.5-pro',
          provider: 'claude',
          label: 'MiMo',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
        // A2:claude opus 支持 1M 由后端 registry 下发 supports1MContext=true(取代前端"claude- 非 haiku"字符串推断)。
        {
          id: 'claude-opus-4-8',
          identifier: 'claude-opus-4-8',
          provider: 'claude',
          label: 'Opus',
          contextWindow: 200_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
      ],
    });
  });

  // ── /clear (new_session) ──

  it('/clear creates a new session locally without sending to the CLI', async () => {
    const forceCreateNewSession = vi.fn();
    const handled = await run(createOptions({ forceCreateNewSession }), '/clear');

    expect(handled).toBe(true);
    expect(forceCreateNewSession).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  // ── /resume (open_history) ──

  it('/resume opens the history view', async () => {
    const setCurrentView = vi.fn();
    const handled = await run(createOptions({ setCurrentView }), '/resume');

    expect(handled).toBe(true);
    expect(setCurrentView).toHaveBeenCalledWith('history');
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  // ── /plan (plan_mode) ──

  it('/plan switches to plan mode with an info toast', async () => {
    const handleModeSelect = vi.fn();
    const addToast = vi.fn();
    const handled = await run(createOptions({ handleModeSelect, addToast }), '/plan');

    expect(handled).toBe(true);
    expect(handleModeSelect).toHaveBeenCalledWith('plan');
    expect(addToast).toHaveBeenCalledWith(expect.any(String), 'info');
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('/plan is not local for Codex (backend omits localAction) and falls through', async () => {
    resetSlashCommandsState();
    seedCommands(CODEX_COMMANDS);

    const handleModeSelect = vi.fn();
    const handled = await run(createOptions({ handleModeSelect }), '/plan');

    expect(handled).toBe(false);
    expect(handleModeSelect).not.toHaveBeenCalled();
  });

  // ── /model (model_picker) ──

  it('/model opens the model selector', async () => {
    const openModelSelect = vi.fn();
    const handled = await run(
      createOptions({ chatInputRef: { current: { openModelSelect } } }),
      '/model',
    );

    expect(handled).toBe(true);
    expect(openModelSelect).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  // ── /help (help) ──

  it('/help appends a user message and an assistant command overview', async () => {
    const setMessages = vi.fn();
    const handled = await run(createOptions({ setMessages }), '/help');

    expect(handled).toBe(true);
    expect(setMessages).toHaveBeenCalledTimes(1);
    const updater = setMessages.mock.calls[0][0] as (prev: unknown[]) => Array<{ type: string; content: string }>;
    const next = updater([]);
    expect(next).toHaveLength(2);
    expect(next[0].type).toBe('user');
    expect(next[0].content).toBe('/help');
    expect(next[1].type).toBe('assistant');
    expect(next[1].content).toContain('/clear');
    expect(next[1].content).toContain('/compact');
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  // ── /context (context_usage) ──

  it('/context sends get_context_usage with base model when longContext is disabled', async () => {
    const handled = await run(
      createOptions({ selectedModel: 'claude-opus-4-8', longContextEnabled: false }),
      '/context',
    );

    expect(handled).toBe(true);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const bridgePayload = parseBridgeCall((window.sendToJava as any).mock.calls[0][0]);
    expect(bridgePayload.type).toBe('get_context_usage');
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('claude-opus-4-8');
    expect(payload.longContextEnabled).toBe(false);
    expect(payload.requestId).toBeTruthy();
  });

  it('/context sends get_context_usage with longContextEnabled intent when enabled', async () => {
    // D5:前端不再构造 [1m];上送 stripped model + longContextEnabled 意图,
    // 后端 GetContextUsageActionHandler 据此权威追加 [1m](与 set_session_model 范式一致)。
    await run(
      createOptions({ selectedModel: 'claude-opus-4-8', longContextEnabled: true }),
      '/context',
    );

    const bridgePayload = parseBridgeCall((window.sendToJava as any).mock.calls[0][0]);
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('claude-opus-4-8');
    expect(payload.longContextEnabled).toBe(true);
  });

  it('/context sends longContextEnabled intent for registry Claude models that support 1M', async () => {
    // D5:registry 模型(mimo,contextWindow=1M,supports1M=true)→ longContextEnabled=true 上送,
    // [1m] 由后端据意图追加;model 原样上送(无 [1m])。
    await run(
      createOptions({ selectedModel: 'mimo-v2.5-pro', longContextEnabled: true }),
      '/context',
    );

    const bridgePayload = parseBridgeCall((window.sendToJava as any).mock.calls[0][0]);
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('mimo-v2.5-pro');
    expect(payload.longContextEnabled).toBe(true);
  });

  it('/context opens dialog with loading state before sending bridge event', async () => {
    const openContextUsageDialog = vi.fn();
    await run(createOptions({ openContextUsageDialog }), '/context');

    expect(openContextUsageDialog).toHaveBeenCalledTimes(1);
    expect(openContextUsageDialog).toHaveBeenCalledWith(
      expect.any(String),
      true, // loading = true
    );
    // Dialog opened BEFORE bridge event sent
    expect(openContextUsageDialog.mock.invocationCallOrder[0]).toBeLessThan(
      (window.sendToJava as any).mock.invocationCallOrder[0],
    );
  });

  it('/context closes dialog with error toast when bridge is unavailable', async () => {
    // Don't set window.sendToJava → bridge unavailable
    delete (window as any).sendToJava;

    const addToast = vi.fn();
    const closeContextUsageDialog = vi.fn().mockReturnValue(true);
    const handled = await run(
      createOptions({ addToast, closeContextUsageDialog }),
      '/context',
    );

    expect(handled).toBe(true);
    expect(closeContextUsageDialog).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(expect.any(String), 'error');
  });

  // ── pass-through commands ──

  it('returns false for commands without localAction (forwarded to the CLI)', async () => {
    const forceCreateNewSession = vi.fn();
    const handled = await run(createOptions({ forceCreateNewSession }), '/compact');

    expect(handled).toBe(false);
    expect(forceCreateNewSession).not.toHaveBeenCalled();
  });

  it('returns false for unknown commands', async () => {
    const handled = await run(createOptions(), '/nonexistent');

    expect(handled).toBe(false);
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('returns false for non-slash text', async () => {
    const handled = await run(createOptions(), 'hello');

    expect(handled).toBe(false);
  });
});
