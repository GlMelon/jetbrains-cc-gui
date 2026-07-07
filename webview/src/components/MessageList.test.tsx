import { fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock } from '../types';
import { MessageList } from './MessageList';

// Mock MessageItem to keep this suite focused on list-level paging behaviour.
vi.mock('./MessageItem', () => ({
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

vi.mock('./MessageItem/MessageUsageStats', () => ({
  MessageUsageStats: () => <div data-testid="usage-stats">usage</div>,
}));

vi.mock('./MessageItem/AssistantStreamingFooter', () => ({
  AssistantStreamingFooter: ({ elapsedMs, startedAt }: { elapsedMs?: number; startedAt?: number | null }) => (
    <div
      data-testid="streaming-footer"
      data-elapsed-ms={elapsedMs ?? ''}
      data-started-at={startedAt ?? ''}
    >
      streaming
    </div>
  ),
}));

vi.mock('./WaitingIndicator', () => ({
  default: () => <div data-testid="waiting-indicator">waiting</div>,
}));

vi.mock('./ContextMenu', () => ({
  ContextMenu: () => null,
}));

vi.mock('../hooks/useContextMenu.js', () => ({
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
  return key;
}) as never;

function makeMessages(count: number, idPrefix = 'm'): ClaudeMessage[] {
  return Array.from({ length: count }, (_, i) => ({
    type: i % 2 === 0 ? 'user' : 'assistant',
    content: `message ${i}`,
    id: `${idPrefix}-${i}`,
  }) as unknown as ClaudeMessage);
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
    />
  );
}

describe('MessageList paged collapse', () => {
  afterEach(cleanup);

  it('renders all messages when total ≤ visible window (15)', () => {
    renderList(makeMessages(10));
    expect(screen.getAllByTestId('message-item')).toHaveLength(10);
    expect(screen.queryByText(/Show.*earlier/)).toBeNull();
  });

  it('collapses earlier messages when total > visible window', () => {
    const { container } = renderList(makeMessages(50));
    // Visible: last 15 messages
    expect(screen.getAllByTestId('message-item')).toHaveLength(15);
    // Indicator shows next chunk size (30) and remaining total (35) appended
    const indicator = container.querySelector('.collapsed-messages-indicator');
    expect(indicator).toBeTruthy();
    expect(indicator?.textContent).toContain('Show 30 earlier');
    expect(indicator?.textContent).toContain('(35)');
  });

  it('reveals one chunk per click instead of expanding everything', () => {
    const { container } = renderList(makeMessages(100));
    expect(screen.getAllByTestId('message-item')).toHaveLength(15);

    const indicator = container.querySelector('.collapsed-messages-indicator');
    expect(indicator?.textContent).toContain('Show 30 earlier');
    fireEvent.click(indicator!);
    // 15 + 30 chunk
    expect(screen.getAllByTestId('message-item')).toHaveLength(45);

    fireEvent.click(container.querySelector('.collapsed-messages-indicator')!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(75);
  });

  it('removes the indicator once everything is revealed', () => {
    const { container } = renderList(makeMessages(40));
    const indicator = container.querySelector('.collapsed-messages-indicator');
    // 40 - 15 = 25 collapsed → next click size = min(30, 25) = 25
    expect(indicator?.textContent).toContain('Show 25 earlier');
    // Total <= chunk → no extra " (N)" suffix
    expect(indicator?.textContent).not.toMatch(/\(\d+\)/);

    fireEvent.click(indicator!);
    expect(screen.getAllByTestId('message-item')).toHaveLength(40);
    expect(container.querySelector('.collapsed-messages-indicator')).toBeNull();
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
      />
    );

    // Initial: 60 - 15 = 45 collapsed
    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(45);

    // Reveal one chunk
    const indicator = container.querySelector('.collapsed-messages-indicator');
    fireEvent.click(indicator!);
    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(15);

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
      />
    );
    // Reset → 50 - 15 = 35 collapsed
    expect(onCollapsedCountChange).toHaveBeenLastCalledWith(35);
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
      />
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
      />
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
      />
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
      />
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
      />
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
    expect(responseGroup?.querySelectorAll('[data-render-mode="response-segment"]')).toHaveLength(3);
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
      />
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
      />
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
      />
    );

    expect(screen.getByTestId('streaming-footer').getAttribute('data-started-at')).toBe(String(loadingStartTime));
  });
});
