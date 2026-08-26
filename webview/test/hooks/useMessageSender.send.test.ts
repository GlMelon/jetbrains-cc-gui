// @vitest-environment happy-dom

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useMessageSender } from '../../src/hooks/useMessageSender';

/**
 * Send-payload shape tests for useMessageSender.
 *
 * Plugin-local slash commands (/context, /clear, /plan, ...) are no longer
 * intercepted here — they moved to useLocalSlashCommands (see
 * useLocalSlashCommands.test.ts), driven by backend-annotated localAction
 * metadata. This hook only builds and sends regular messages.
 */
describe('useMessageSender - send payload', () => {
  const t = ((key: string, opts?: any) => opts?.defaultValue ?? key) as any;
  const parseBridgeCall = (call: string) => JSON.parse(call) as { type: string; content: string };

  const createOptions = (overrides: Record<string, unknown> = {}) => ({
    t,
    addToast: vi.fn(),
    currentProvider: 'claude',
    selectedModel: 'claude-opus-4-8',
    permissionMode: 'default',
    selectedAgent: null,
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
    ...overrides,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any;

  beforeEach(() => {
    window.sendToJava = vi.fn();
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
});
