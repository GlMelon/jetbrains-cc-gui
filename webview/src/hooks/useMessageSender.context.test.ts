// @vitest-environment happy-dom

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useMessageSender } from './useMessageSender';
import type { UseMessageSenderOptions } from './useMessageSender';
import { __setModelRegistryForTests } from '../utils/modelRegistry';

describe('useMessageSender - /context command', () => {
  const t = ((key: string, opts?: any) => opts?.defaultValue ?? key) as any;
  const parseBridgeCall = (call: string) => JSON.parse(call) as { type: string; content: string };

  const createOptions = (overrides: Partial<UseMessageSenderOptions> = {}): UseMessageSenderOptions => ({
    t,
    addToast: vi.fn(),
    currentProvider: 'claude',
    selectedModel: 'claude-opus-4-8',
    permissionMode: 'default',
    selectedAgent: null,
    sdkStatusLoaded: true,
    sentAttachmentsRef: { current: new Map() },
    chatInputRef: { current: null },
    messagesContainerRef: { current: null },
    isUserAtBottomRef: { current: true },
    userPausedRef: { current: false },
    isStreamingRef: { current: false },
    setMessages: vi.fn(),
    setLoading: vi.fn(),
    setLoadingStartTime: vi.fn(),
    setStreamingActive: vi.fn(),
    setCurrentView: vi.fn(),
    forceCreateNewSession: vi.fn(),
    handleModeSelect: vi.fn(),
    longContextEnabled: false,
    openContextUsageDialog: vi.fn(),
    closeContextUsageDialog: vi.fn().mockReturnValue(true),
    ...overrides,
  });

  beforeEach(() => {
    window.sendToJava = vi.fn();
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5-pro',
          provider: 'claude',
          label: 'MiMo',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
        // A2:claude opus 支持 1M 由后端 registry 下发 supports1MContext=true(取代前端"claude- 非 haiku"字符串推断)。
        {
          id: 'claude-opus-4-7',
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

  it('sends get_context_usage with base model when longContext is disabled', () => {
    const opts = createOptions({
      selectedModel: 'claude-opus-4-8',
      longContextEnabled: false,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const call = (window.sendToJava as any).mock.calls[0][0] as string;
    const bridgePayload = parseBridgeCall(call);
    expect(bridgePayload.type).toBe('get_context_usage');
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('claude-opus-4-7');
    expect(payload.longContextEnabled).toBe(false);
    expect(payload.requestId).toBeTruthy();
  });

  it('sends get_context_usage with longContextEnabled intent when longContext is enabled', () => {
    // D5:前端不再构造 [1m];上送 stripped model + longContextEnabled 意图,
    // 后端 GetContextUsageActionHandler 据此权威追加 [1m](与 set_session_model 范式一致)。
    const opts = createOptions({
      selectedModel: 'claude-opus-4-8',
      longContextEnabled: true,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const call = (window.sendToJava as any).mock.calls[0][0] as string;
    const bridgePayload = parseBridgeCall(call);
    expect(bridgePayload.type).toBe('get_context_usage');
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('claude-opus-4-7');
    expect(payload.longContextEnabled).toBe(true);
  });

  it('sends get_context_usage with longContextEnabled intent for registry Claude models that support 1M', () => {
    // D5:registry 模型(mimo,contextWindow=1M,supports1M=true)→ longContextEnabled=true 上送,
    // [1m] 由后端据意图追加;model 原样上送(无 [1m])。
    const opts = createOptions({
      selectedModel: 'mimo-v2.5-pro',
      longContextEnabled: true,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    const call = (window.sendToJava as any).mock.calls[0][0] as string;
    const bridgePayload = parseBridgeCall(call);
    const payload = JSON.parse(bridgePayload.content);
    expect(payload.model).toBe('mimo-v2.5-pro');
    expect(payload.longContextEnabled).toBe(true);
  });

  it('opens dialog with loading state before sending bridge event', () => {
    const openContextUsageDialog = vi.fn();
    const opts = createOptions({ openContextUsageDialog });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

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

  it('shows warning toast and does not send bridge event for Codex provider', () => {
    const addToast = vi.fn();
    const opts = createOptions({
      currentProvider: 'codex',
      addToast,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).not.toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(
      expect.stringContaining('Claude'),
      'warning',
    );
  });

  it('sends context usage request without frontend invocation mode gating', () => {
    const addToast = vi.fn();
    const opts = createOptions({ addToast });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const bridgePayload = parseBridgeCall((window.sendToJava as any).mock.calls[0][0]);
    expect(bridgePayload.type).toBe('get_context_usage');
    expect(addToast).not.toHaveBeenCalledWith(
      expect.stringContaining('CLI mode'),
      expect.anything(),
    );
  });

    it('allows normal Claude messages without frontend invocation mode state', () => {
        const addToast = vi.fn();
        const opts = createOptions({addToast});

        const {result} = renderHook(() => useMessageSender(opts));

        act(() => {
            result.current.handleSubmit('hello');
        });

        const calls = (window.sendToJava as any).mock.calls.map(([payload]: [string]) => parseBridgeCall(payload));
        const sendMessageCall = calls.find((payload: { type: string }) => payload.type === 'send_message');
        expect(sendMessageCall).toBeTruthy();
        expect(addToast).not.toHaveBeenCalledWith(
            expect.stringContaining('Invocation mode'),
            'error',
        );
    });

    it('does not include invocationMode in normal send payload', () => {
        const opts = createOptions({currentProvider: 'codex'});

        const {result} = renderHook(() => useMessageSender(opts));

        act(() => {
            result.current.handleSubmit('hello');
        });

        const calls = (window.sendToJava as any).mock.calls.map(([payload]: [string]) => parseBridgeCall(payload));
        const sendMessageCall = calls.find((payload: { type: string }) => payload.type === 'send_message');
        const payload = JSON.parse(sendMessageCall!.content);
        expect(payload).not.toHaveProperty('invocationMode');
    });

    it('does not include invocationMode in attachment send payload', () => {
        const opts = createOptions({currentProvider: 'codex'});

        const {result} = renderHook(() => useMessageSender(opts));

        act(() => {
            result.current.handleSubmit('hello', [{
                id: 'att-1',
                fileName: 'note.txt',
                mediaType: 'text/plain',
                data: 'aGVsbG8=',
            }]);
        });

        const calls = (window.sendToJava as any).mock.calls.map(([payload]: [string]) => parseBridgeCall(payload));
        const sendMessageCall = calls.find((payload: { type: string }) => payload.type === 'send_message_with_attachments');
        const payload = JSON.parse(sendMessageCall!.content);
        expect(payload).not.toHaveProperty('invocationMode');
    });

    it('does not include permissionMode in normal send payload', () => {
        const opts = createOptions({
            currentProvider: 'codex',
            permissionMode: 'bypassPermissions',
        });
        const {result} = renderHook(() => useMessageSender(opts));

        act(() => {
            result.current.handleSubmit('hello');
        });

        const calls = (window.sendToJava as any).mock.calls.map(([payload]: [string]) => parseBridgeCall(payload));
        const sendMessageCall = calls.find((payload: { type: string }) => payload.type === 'send_message');
        const payload = JSON.parse(sendMessageCall!.content);
        expect(payload).not.toHaveProperty('permissionMode');
    });

  it('closes dialog with error toast when bridge is unavailable', () => {
    // Don't set window.sendToJava → bridge unavailable
    delete (window as any).sendToJava;

    const addToast = vi.fn();
    const closeContextUsageDialog = vi.fn().mockReturnValue(true);
    const opts = createOptions({ addToast, closeContextUsageDialog });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(closeContextUsageDialog).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(
      expect.any(String),
      'error',
    );
  });

  it('allows sending while SDK status is still loading and shows an informational toast', () => {
    const addToast = vi.fn();
    const opts = createOptions({
      currentProvider: 'codex',
      sdkStatusLoaded: false,
      addToast,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    expect(window.sendToJava).toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledWith(
      expect.stringContaining('backend will verify'),
      'info',
    );
  });

  it('does not block sends when frontend SDK install state is unavailable', () => {
    const addToast = vi.fn();
    const opts = createOptions({
      currentProvider: 'codex',
      addToast,
    } as Partial<UseMessageSenderOptions>);

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    expect(window.sendToJava).toHaveBeenCalled();
    expect(addToast).not.toHaveBeenCalledWith(
      expect.stringContaining('SDK'),
      'warning',
    );
  });
});
