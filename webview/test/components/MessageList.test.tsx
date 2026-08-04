import { act, fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock } from '../../src/types';
import { MessageList } from '../../src/components/MessageList';
import { bridgeHub } from '../../src/bridge/hub';
import { DOWNSTREAM } from '../../src/generated/protocol';

// Mock MessageItem to keep this suite focused on list-level paging behaviour.
vi.mock('../../src/components/MessageItem', () => ({
  MessageItem: ({
    messageKey,
    message,
    withinResponseGroup,
    renderMode,
  }: {
    messageKey: string;
    message: ClaudeMessage;
    withinResponseGroup?: boolean;
    renderMode?: string;
  }) => (
    <div
      data-testid="message-item"
      data-key={messageKey}
      data-type={message.type}
      data-within-response-group={withinResponseGroup ? 'true' : 'false'}
      data-render-mode={renderMode ?? 'full'}
    >
      {message.content}
    </div>
  ),
}));

vi.mock('../../src/components/MessageItem/MessageUsageStats', () => ({
  MessageUsageStats: () => <div data-testid="usage-stats">usage</div>,
}));

vi.mock('../../src/components/MessageItem/AssistantStreamingFooter', () => ({
  AssistantStreamingFooter: ({
    elapsedMs,
    startedAt,
  }: {
    elapsedMs?: number;
    startedAt?: number | null;
  }) => (
    <div
      data-testid="streaming-footer"
      data-elapsed-ms={elapsedMs ?? ''}
      data-started-at={startedAt ?? ''}
    >
      streaming
    </div>
  ),
}));

vi.mock('../../src/components/WaitingIndicator', () => ({
  default: () => <div data-testid="waiting-indicator">waiting</div>,
}));

vi.mock('../../src/components/ContextMenu', () => ({
  ContextMenu: () => null,
}));

vi.mock('../../src/hooks/useContextMenu.js', () => ({
  useContextMenu: () => ({
    visible: false,
    x: 0,
    y: 0,
    savedRange: null,
    selectedText: '',
    open: vi.fn(),
    close: vi.fn(),
  }),
  copySelection: vi.fn(),
}));

const t = ((key: string, opts?: Record<string, unknown>) => {
  if (key === 'chat.showEarlierMessages') {
    const count = opts?.count ?? 0;
    return `Show ${count} earlier`;
  }
  if (key === 'chat.showEarlierTurns') {
    return `Show ${opts?.count ?? 0} earlier turns (${opts?.remaining ?? 0} remaining)`;
  }
  if (key === 'chat.loadEarlierTurns') {
    return `Load ${opts?.count ?? 0} earlier turns (${opts?.remaining ?? 0} remaining)`;
  }
  if (key === 'chat.loadingEarlierTurns') return 'Loading earlier turns...';
  return key;
}) as never;

function makeMessages(count: number, idPrefix = 'm'): ClaudeMessage[] {
  return Array.from(
    { length: count },
    (_, i) =>
      ({
        type: i % 2 === 0 ? 'user' : 'assistant',
        content: `message ${i}`,
        id: `${idPrefix}-${i}`,
      }) as unknown as ClaudeMessage,
  );
}

function makeToolDenseTurns(turnCount: number): ClaudeMessage[] {
  return Array.from({ length: turnCount }, (_, turn) => [
    { type: 'user', content: `user ${turn}`, id: `user-${turn}` },
    { type: 'assistant', content: `thinking ${turn}`, id: `thinking-${turn}` },
    {
      type: 'assistant',
      content: `tool ${turn}`,
      id: `tool-${turn}`,
      raw: { content: [{ type: 'tool_use', id: `call-${turn}`, name: 'Read', input: {} }] },
    },
    {
      type: 'user',
      content: '[tool_result]',
      id: `result-${turn}`,
      raw: { content: [{ type: 'tool_result', tool_use_id: `call-${turn}`, content: 'ok' }] },
    },
    { type: 'assistant', content: `answer ${turn}`, id: `answer-${turn}` },
  ]).flat() as unknown as ClaudeMessage[];
}

const noopGetText = (m: ClaudeMessage) => m.content ?? '';
const noopGetBlocks = (_m: ClaudeMessage): ClaudeContentBlock[] => [];
const noopFindToolResult = (_id: string | undefined, _i: number): ToolResultBlock | null => null;
const noopExtractMd = (_m: ClaudeMessage) => '';

function renderList(messages: ClaudeMessage[]) {
  const endRef = createRef<HTMLDivElement>();
  return render(
    <MessageList
      messages={messages}
      streamingActive={false}
      isThinking={false}
      loading={false}
      loadingStartTime={null}
      queueDisplayState="NONE"
      queueAheadCount={0}
      t={t}
      getMessageText={noopGetText}
      getContentBlocks={noopGetBlocks}
      findToolResult={noopFindToolResult}
      extractMarkdownContent={noopExtractMd}
      messagesEndRef={endRef}
    />,
  );
}

describe('MessageList paged collapse', () => {
  afterEach(() => {
    cleanup();
    delete window.sendToJava;
  });

  it('renders all messages when there are at most five user turns', () => {
    renderList(makeMessages(10));
    expect(screen.getAllByTestId('message-item')).toHaveLength(10);
    expect(screen.queryByText(/Show.*earlier/)).toBeNull();
  });

  it('collapses earlier complete turns when there are more than five user turns', () => {
    const { container } = renderList(makeMessages(50));
    expect(screen.getAllByTestId('message-item')).toHaveLength(10);
    const indicator = container.querySelector('.collapsed-messages-indicator');
    expect(indicator).toBeTruthy();
    expect(indicator?.textContent).toBe('Show 5 earlier turns (20 remaining)');
  });

  it('reveals five complete turns per click instead of expanding everything', () => {
    const { container } = renderList(makeMessages(100));
    expect(screen.getAllByTestId('message-item')).toHaveLength(10);

    const indicator = container.querySelector('.collapsed-messages-indicator');
    expect(indicator?.textContent).toBe('Show 5 earlier turns (45 remaining)');
    fireEvent.click(indicator!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(20);

    fireEvent.click(container.querySelector('.collapsed-messages-indicator')!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(30);
  });

  it('removes the indicator once everything is revealed', () => {
    const { container } = renderList(makeMessages(16));
    const indicator = container.querySelector('.collapsed-messages-indicator');
    expect(indicator?.textContent).toBe('Show 3 earlier turns (3 remaining)');

    fireEvent.click(indicator!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(16);
    expect(container.querySelector('.collapsed-messages-indicator')).toBeNull();
  });

  it('never starts rendering in the middle of an assistant and tool chain', () => {
    const { container } = renderList(makeToolDenseTurns(8));
    const visible = screen.getAllByTestId('message-item');

    expect(visible).toHaveLength(25);
    expect(visible[0].textContent).toBe('user 3');
    expect(container.querySelector('.collapsed-messages-indicator')?.textContent).toBe(
      'Show 3 earlier turns (3 remaining)',
    );
  });

  it('tolerates malformed raw content blocks from history transport', () => {
    const messages = makeMessages(14);
    messages[0] = {
      ...messages[0],
      raw: { content: [null, 'unexpected'] },
    } as unknown as ClaudeMessage;

    expect(() => renderList(messages)).not.toThrow();
    expect(screen.getAllByTestId('message-item')).toHaveLength(10);
  });

  it('reports collapsedCount changes to parent for anchor rail sync', () => {
    const onCollapsedCountChange = vi.fn();
    const messages = makeMessages(60);
    const endRef = createRef<HTMLDivElement>();
    const { rerender, container } = render(
      <MessageList
        messages={messages}
        streamingActive={false}
        isThinking={false}
        loading={false}
        loadingStartTime={null}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
        onCollapsedCountChange={onCollapsedCountChange}
      />,
    );

    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(50);

    // Reveal one chunk
    const indicator = container.querySelector('.collapsed-messages-indicator');
    fireEvent.click(indicator!);
    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(40);

    // Trigger a session switch via first-message-id change
    rerender(
      <MessageList
        messages={makeMessages(50, 'session2')}
        streamingActive={false}
        isThinking={false}
        loading={false}
        loadingStartTime={null}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
        onCollapsedCountChange={onCollapsedCountChange}
      />,
    );
    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(40);
  });

  it('resets revealed turns when id-less history messages switch sessions', () => {
    const firstSession = makeMessages(40).map(({ id: _id, ...message }, index) => ({
      ...message,
      timestamp: `2026-07-16T10:00:${String(index).padStart(2, '0')}.000Z`,
    })) as ClaudeMessage[];
    const secondSession = makeMessages(40).map(({ id: _id, ...message }, index) => ({
      ...message,
      timestamp: `2026-07-17T10:00:${String(index).padStart(2, '0')}.000Z`,
    })) as ClaudeMessage[];
    const endRef = createRef<HTMLDivElement>();
    const { container, rerender } = render(
      <MessageList
        messages={firstSession}
        streamingActive={false}
        isThinking={false}
        loading={false}
        loadingStartTime={null}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    fireEvent.click(container.querySelector('.collapsed-messages-indicator')!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(20);

    rerender(
      <MessageList
        messages={secondSession}
        streamingActive={false}
        isThinking={false}
        loading={false}
        loadingStartTime={null}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.getAllByTestId('message-item')).toHaveLength(10);
  });

  it('requests the previous disk page only after all loaded turns are revealed', () => {
    const sendToJava = vi.fn();
    window.sendToJava = sendToJava;
    const endRef = createRef<HTMLDivElement>();
    const { container } = render(
      <MessageList
        messages={makeMessages(20)}
        streamingActive={false}
        isThinking={false}
        loading={false}
        loadingStartTime={null}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
        currentProvider="codex"
        currentSessionId="session-1"
      />,
    );

    act(() => {
      bridgeHub.dispatch(
        DOWNSTREAM.HISTORY_CODEX_PAGE_INFO,
        JSON.stringify({
          pageId: 'page-1',
          sessionId: 'session-1',
          mode: 'replace',
          fromTurn: 70,
          toTurn: 100,
          totalTurns: 100,
          hasMore: true,
          loadedMessageCount: 20,
          cursorReset: false,
        }),
      );
    });

    fireEvent.click(container.querySelector('.collapsed-messages-indicator')!);
    expect(container.querySelector('.collapsed-messages-indicator')?.textContent).toBe(
      'Load 30 earlier turns (70 remaining)',
    );

    fireEvent.click(container.querySelector('.collapsed-messages-indicator')!);
    expect(sendToJava).toHaveBeenCalledWith(
      JSON.stringify({
        type: 'load_codex_history_page',
        content: JSON.stringify({ sessionId: 'session-1', beforeTurn: 70 }),
      }),
    );
    expect(container.querySelector('.collapsed-messages-indicator')?.textContent).toBe(
      'Loading earlier turns...',
    );
  });
});

describe('MessageList container behaviour', () => {
  afterEach(cleanup);

  it('marks the root as a flex column layout boundary for message alignment', () => {
    const { container } = renderList(makeMessages(3));
    expect(container.firstElementChild?.classList.contains('message-list')).toBe(true);
  });

  it('uses the latest message index for isLast even when paginated', () => {
    const messages = makeMessages(40);
    renderList(messages);
    const items = screen.getAllByTestId('message-item');
    const last = items[items.length - 1];
    // The last item must correspond to messages[39]
    expect(last.textContent).toBe('message 39');
  });

  it('hides waiting indicator for non-queued loading', () => {
    const endRef = createRef<HTMLDivElement>();
    render(
      <MessageList
        messages={makeMessages(3)}
        streamingActive={false}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );
    expect(screen.queryByTestId('waiting-indicator')).toBeNull();
  });

  it('renders waiting indicator while queued', () => {
    const endRef = createRef<HTMLDivElement>();
    render(
      <MessageList
        messages={makeMessages(3)}
        streamingActive={false}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="QUEUED"
        queueAheadCount={1}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );
    expect(screen.getByTestId('waiting-indicator')).toBeTruthy();
  });

  it('hides waiting indicator when assistant response status is active', () => {
    const endRef = createRef<HTMLDivElement>();
    render(
      <MessageList
        messages={[
          ...makeMessages(2),
          {
            type: 'assistant',
            content: '',
            isStreaming: true,
            __assistantResponseStatus: {
              phase: 'thinking',
              providerLabel: 'Codex',
              title: '正在思考',
              description: '正在分析上下文',
              elapsedMs: 2400,
              active: true,
            },
          },
        ]}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.queryByTestId('waiting-indicator')).toBeNull();
  });

  it('hides waiting indicator for the inline streaming placeholder fallback', () => {
    const endRef = createRef<HTMLDivElement>();
    render(
      <MessageList
        messages={[
          ...makeMessages(2),
          {
            type: 'assistant',
            content: '',
            isStreaming: true,
          },
        ]}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.queryByTestId('waiting-indicator')).toBeNull();
  });

  it('hides waiting indicator while assistant content is streaming inline', () => {
    const endRef = createRef<HTMLDivElement>();
    render(
      <MessageList
        messages={[
          ...makeMessages(2),
          {
            type: 'assistant',
            content: 'partial response',
            isStreaming: true,
          },
        ]}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.queryByTestId('waiting-indicator')).toBeNull();
  });
});

describe('MessageList response grouping', () => {
  afterEach(cleanup);

  it('renders adjacent assistant messages with the same response id inside one response container', () => {
    const messages: ClaudeMessage[] = [
      { type: 'user', content: 'please update thresholds', id: 'u-1' },
      {
        type: 'assistant',
        content: 'read file',
        id: 'a-1',
        __responseId: 'response-1',
        raw: { usage: { input_tokens: 10, output_tokens: 20 } } as any,
      },
      {
        type: 'assistant',
        content: 'edit file',
        id: 'a-2',
        __responseId: 'response-1',
        raw: { usage: { input_tokens: 30, output_tokens: 40 } } as any,
      },
      {
        type: 'assistant',
        content: 'final summary',
        id: 'a-3',
        __responseId: 'response-1',
        raw: { usage: { input_tokens: 50, output_tokens: 60 } } as any,
      },
    ];

    const { container } = renderList(messages);

    const responseGroup = container.querySelector('.assistant-response-group');
    expect(responseGroup).toBeTruthy();
    expect(responseGroup?.classList.contains('message')).toBe(true);
    expect(responseGroup?.classList.contains('assistant')).toBe(true);
    expect(responseGroup?.querySelectorAll('.assistant-response-segment')).toHaveLength(3);
    expect(responseGroup?.querySelectorAll('[data-render-mode="response-segment"]')).toHaveLength(
      3,
    );
    expect(responseGroup?.querySelectorAll('[data-testid="usage-stats"]')).toHaveLength(1);
  });

  it('shows streaming footer instead of final usage stats for the active response group', () => {
    const messages: ClaudeMessage[] = [
      { type: 'user', content: 'please explain', id: 'u-1' },
      {
        type: 'assistant',
        content: 'first segment',
        id: 'a-1',
        __responseId: 'response-1',
      },
      {
        type: 'assistant',
        content: 'partial final segment',
        id: 'a-2',
        isStreaming: true,
        __responseId: 'response-1',
        __assistantResponseStatus: {
          phase: 'responding',
          providerLabel: 'Codex',
          title: '正在输出',
          elapsedMs: 2200,
          active: true,
        },
      },
    ];
    const endRef = createRef<HTMLDivElement>();

    const { container } = render(
      <MessageList
        messages={messages}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    const responseGroup = container.querySelector('.assistant-response-group');
    const responseContent = responseGroup?.querySelector('.assistant-response-content');
    const streamingFooter = responseGroup?.querySelector('[data-testid="streaming-footer"]');
    expect(responseGroup?.querySelectorAll('[data-testid="streaming-footer"]')).toHaveLength(1);
    expect(responseContent?.contains(streamingFooter ?? null)).toBe(true);
    expect(responseContent?.lastElementChild).toBe(streamingFooter);
    expect(responseGroup?.querySelectorAll('[data-testid="usage-stats"]')).toHaveLength(0);
  });

  it('does not show streaming footer for a status-only response group placeholder', () => {
    const messages: ClaudeMessage[] = [
      { type: 'user', content: 'please explain', id: 'u-1' },
      {
        type: 'assistant',
        content: '',
        id: 'a-1',
        isStreaming: true,
        __responseId: 'response-1',
        __assistantResponseStatus: {
          phase: 'thinking',
          providerLabel: 'Codex',
          title: 'Understanding your request',
          description: 'Analyzing context',
          elapsedMs: 1400,
          active: true,
        },
      },
    ];
    const endRef = createRef<HTMLDivElement>();

    render(
      <MessageList
        messages={messages}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={Date.now()}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.queryByTestId('streaming-footer')).toBeNull();
  });

  it('passes loading start time to streaming footer as elapsed fallback', () => {
    const loadingStartTime = 12345;
    const messages: ClaudeMessage[] = [
      { type: 'user', content: 'please explain', id: 'u-1' },
      {
        type: 'assistant',
        content: 'partial final segment',
        id: 'a-1',
        isStreaming: true,
        __responseId: 'response-1',
      },
    ];
    const endRef = createRef<HTMLDivElement>();

    render(
      <MessageList
        messages={messages}
        streamingActive={true}
        isThinking={false}
        loading={true}
        loadingStartTime={loadingStartTime}
        queueDisplayState="NONE"
        queueAheadCount={0}
        t={t}
        getMessageText={noopGetText}
        getContentBlocks={noopGetBlocks}
        findToolResult={noopFindToolResult}
        extractMarkdownContent={noopExtractMd}
        messagesEndRef={endRef}
      />,
    );

    expect(screen.getByTestId('streaming-footer').getAttribute('data-started-at')).toBe(
      String(loadingStartTime),
    );
  });
});

function createStreamingMessageList(
  initialMessages: ClaudeMessage[],
  initialStreamingActive: boolean,
  isUserAtBottomRef: { current: boolean },
  initialSessionId = 'session-1',
) {
  const endRef = createRef<HTMLDivElement>();
  const renderElement = (
    messages: ClaudeMessage[],
    streamingActive: boolean,
    currentSessionId: string,
  ) => (
    <MessageList
      messages={messages}
      streamingActive={streamingActive}
      isThinking={false}
      loading={streamingActive}
      loadingStartTime={streamingActive ? 1 : null}
      queueDisplayState="NONE"
      queueAheadCount={0}
      t={t}
      getMessageText={noopGetText}
      getContentBlocks={noopGetBlocks}
      findToolResult={noopFindToolResult}
      extractMarkdownContent={noopExtractMd}
      messagesEndRef={endRef}
      currentSessionId={currentSessionId}
      isUserAtBottomRef={isUserAtBottomRef}
    />
  );
  const view = render(renderElement(initialMessages, initialStreamingActive, initialSessionId));

  return {
    ...view,
    rerenderList: (
      messages: ClaudeMessage[],
      streamingActive: boolean,
      currentSessionId = initialSessionId,
    ) => view.rerender(renderElement(messages, streamingActive, currentSessionId)),
  };
}

function streamingTurn(content: string): ClaudeMessage[] {
  return [
    { type: 'user', content: 'question', id: 'stream-user' },
    { type: 'assistant', content, id: 'stream-assistant', isStreaming: true },
  ] as ClaudeMessage[];
}

describe('MessageList streaming aria-live announcer', () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('exposes a polite atomic live region', () => {
    const bottomRef = { current: false };
    createStreamingMessageList(streamingTurn('Hello'), true, bottomRef);

    const announcer = screen.getByTestId('stream-announcer');
    expect(announcer.getAttribute('role')).toBe('status');
    expect(announcer.getAttribute('aria-live')).toBe('polite');
    expect(announcer.getAttribute('aria-atomic')).toBe('true');
  });

  it('suppresses announcements at the bottom and announces later increments off-bottom', () => {
    vi.useFakeTimers();
    const bottomRef = { current: true };
    const view = createStreamingMessageList(streamingTurn('Hello'), true, bottomRef);

    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByTestId('stream-announcer').textContent).toBe('');

    bottomRef.current = false;
    view.rerenderList(streamingTurn('Hello world'), true);
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByTestId('stream-announcer').textContent).toBe('world');
  });

  it('throttles token updates without rebuilding the active interval', () => {
    vi.useFakeTimers();
    const setIntervalSpy = vi.spyOn(window, 'setInterval');
    const bottomRef = { current: false };
    const view = createStreamingMessageList(streamingTurn('One'), true, bottomRef);

    view.rerenderList(streamingTurn('One two'), true);
    view.rerenderList(streamingTurn('One two three'), true);
    expect(setIntervalSpy).toHaveBeenCalledTimes(1);

    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByTestId('stream-announcer').textContent).toBe('One two three');
  });

  it('announces the final pending increment immediately when streaming ends', () => {
    vi.useFakeTimers();
    const bottomRef = { current: false };
    const view = createStreamingMessageList(streamingTurn('Hello'), true, bottomRef);

    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByTestId('stream-announcer').textContent).toBe('Hello');

    view.rerenderList(streamingTurn('Hello final words'), false);
    expect(screen.getByTestId('stream-announcer').textContent).toBe('final words');
  });

  it('clears stale announcements on turn or session reset', () => {
    vi.useFakeTimers();
    const bottomRef = { current: false };
    const view = createStreamingMessageList(streamingTurn('Old response'), true, bottomRef);

    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByTestId('stream-announcer').textContent).toBe('Old response');

    view.rerenderList(
      [{ type: 'user', content: 'new turn', id: 'new-user' }] as ClaudeMessage[],
      true,
    );
    expect(screen.getByTestId('stream-announcer').textContent).toBe('');

    view.rerenderList([], false, 'session-2');
    expect(screen.getByTestId('stream-announcer').textContent).toBe('');
  });

  it('clears the stable interval on unmount', () => {
    vi.useFakeTimers();
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval');
    const bottomRef = { current: false };
    const view = createStreamingMessageList(streamingTurn('Hello'), true, bottomRef);

    expect(vi.getTimerCount()).toBeGreaterThan(0);
    view.unmount();

    expect(clearIntervalSpy).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
