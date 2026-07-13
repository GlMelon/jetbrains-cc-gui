import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ClaudeContentBlock, ClaudeMessage, ToolResultBlock } from '../../types';
import { extractMarkdownContent } from '../../utils/copyUtils';
import { MessageItem } from './MessageItem';

vi.mock('../MarkdownBlock', () => ({
  default: ({ content }: { content: string }) => <div data-testid="markdown-block">{content}</div>,
}));

vi.mock('../toolBlocks', () => ({
  ReadToolBlock: () => <div data-testid="read-tool-block">read</div>,
  ReadToolGroupBlock: () => <div data-testid="read-tool-group-block">read-group</div>,
  EditToolBlock: () => <div data-testid="edit-tool-block">edit</div>,
  EditToolGroupBlock: () => <div data-testid="edit-tool-group-block">edit-group</div>,
  BashToolBlock: () => <div data-testid="bash-tool-block">bash</div>,
  BashToolGroupBlock: () => <div data-testid="bash-tool-group-block">bash-group</div>,
  SearchToolGroupBlock: () => <div data-testid="search-tool-group-block">search-group</div>,
}));

vi.mock('./ContentBlockRenderer', () => ({
  ContentBlockRenderer: ({
    block,
    blockIndex,
    isThinkingExpanded,
    onToggleThinking,
  }: {
    block: ClaudeContentBlock;
    blockIndex: number;
    isThinkingExpanded: boolean;
    onToggleThinking: (blockIndex: number) => void;
  }) => (
    <div
      data-testid={block.type === 'thinking' ? `thinking-${blockIndex}` : `content-block-${block.type}`}
      data-expanded={block.type === 'thinking' ? String(isThinkingExpanded) : undefined}
      onClick={block.type === 'thinking' ? () => onToggleThinking(blockIndex) : undefined}
    >
      {block.type}
      {block.type === 'provider_error' ? `:${(block as any).summary}` : ''}
    </div>
  ),
}));

vi.mock('./ProviderNotConfiguredCard', () => ({
  ProviderNotConfiguredCard: () => <div data-testid="provider-not-configured-card">provider-card</div>,
  isProviderNotConfiguredError: () => false,
}));

const t = ((key: string, opts?: Record<string, unknown>) => {
  const translations: Record<string, string> = {
    'markdown.copyMessage': '复制消息',
    'markdown.copySuccess': '已复制',
    'chat.streamingConnected': '{{provider}} 已连接',
    'chat.streamingResponse': '正在流式输出',
    'chat.messageSections.thinking': '思考',
    'chat.messageSections.tools': '工具',
    'chat.messageSections.output': '输出',
    'chat.totalDuration': '本次耗时',
    'chat.waitingTimedOutDuration': '等待超时',
    'chat.usageStats.input': '输入：',
    'chat.usageStats.output': '输出：',
    'chat.usageStats.total': '总计：',
    'chat.usageStats.duration': '本次耗时',
    'chat.usageStats.tokensUnit': 'tokens',
    'chat.avatarUser': 'You',
    'providers.claude.label': 'Claude Code',
    'providers.codex.label': 'Codex',
    'providers.opencode.label': 'OpenCode',
  };
  let value = translations[key] ?? key;
  if (opts) {
    Object.entries(opts).forEach(([k, v]) => {
      value = value.split(`{{${k}}}`).join(String(v));
    });
  }
  return value;
}) as any;

afterEach(() => {
  vi.useRealTimers();
});

const getMessageText = (message: ClaudeMessage) => message.content ?? '';

const getContentBlocks = (message: ClaudeMessage): ClaudeContentBlock[] => {
  const raw = message.raw;
  if (!raw || typeof raw !== 'object') {
    return [];
  }

  const content = Array.isArray(raw.content)
    ? raw.content
    : Array.isArray(raw.message?.content)
      ? raw.message.content
      : [];

  return content as ClaudeContentBlock[];
};

const findToolResult = (_toolId: string | undefined, _messageIndex: number): ToolResultBlock | null => null;

function renderMessageItem(
  message: ClaudeMessage,
  overrides: Partial<React.ComponentProps<typeof MessageItem>> = {},
) {
  return render(
    <MessageItem
      message={message}
      messageIndex={0}
      messageKey="message-0"
      isLast={false}
      streamingActive={false}
      isThinking={false}
      t={t}
      getMessageText={getMessageText}
      getContentBlocks={getContentBlocks}
      findToolResult={findToolResult}
      extractMarkdownContent={extractMarkdownContent}
      {...overrides}
    />
  );
}

describe('MessageItem copy button visibility', () => {
  it('hides the assistant copy button for tool-only messages', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'Tool: shell_command',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'shell_command',
            input: { cmd: 'git status' },
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-block')).toBeTruthy();
    expect(screen.queryByTestId('content-block-text')).toBeNull();
    expect(screen.queryByRole('button', { name: '复制消息' })).toBeNull();
  });

  it('keeps the assistant copy button when tool output is followed by reply text', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'shell_command',
            input: { cmd: 'git status' },
          },
          {
            type: 'text',
            text: '提交完成。',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-block')).toBeTruthy();
    expect(screen.getByTestId('content-block-text')).toBeTruthy();
    expect(screen.getByRole('button', { name: '复制消息' })).toBeTruthy();
  });

  it('groups consecutive exec_command blocks into the batch command tool block', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'exec_command',
            input: { command: 'git status' },
          },
          {
            type: 'tool_use',
            id: 'tool-2',
            name: 'exec_command',
            input: { command: 'git diff --cached' },
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-group-block')).toBeTruthy();
    expect(screen.queryAllByTestId('content-block-tool_use')).toHaveLength(0);
  });

  it('renders Codex apply_patch tool uses through the edit tool block', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'patch-1',
            name: 'apply_patch',
            input: {
              patch: '*** Update File: README.md\n-old\n+new',
              file_path: 'README.md',
            },
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('edit-tool-block')).toBeTruthy();
    expect(screen.queryAllByTestId('content-block-tool_use')).toHaveLength(0);
  });

  it('labels watchdog-ended assistant duration as a waiting timeout', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'partial answer',
      durationMs: 181_000,
      streamEndSource: 'watchdog',
      streamEndReason: 'stalled',
    };

    renderMessageItem(message);

    expect(screen.getByText('等待超时')).toBeTruthy();
    expect(screen.getByText('3:01')).toBeTruthy();
    expect(screen.queryByText('本次耗时')).toBeNull();
  });

  it('does not show the streaming connection hint for block-reset assistant placeholders', () => {
    vi.useFakeTimers();
    const message: ClaudeMessage = {
      type: 'assistant',
      content: '',
      isStreaming: true,
      __turnId: 2,
      __suppressStreamingConnectHint: true,
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      currentProvider: 'claude',
    });

    act(() => {
      vi.advanceTimersByTime(500);
    });

    expect(screen.queryByText('已连接')).toBeNull();
    vi.useRealTimers();
  });

  it('renders assistant response status inside empty streaming placeholder', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: '',
      isStreaming: true,
      __assistantResponseStatus: {
        phase: 'thinking',
        providerLabel: 'Codex',
        title: '正在思考',
        description: '正在分析上下文',
        elapsedMs: 2800,
        active: true,
      },
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      currentProvider: 'codex',
    });

    expect(screen.getByText('正在思考')).toBeTruthy();
    expect(screen.getByText('正在分析上下文')).toBeTruthy();
    expect(screen.getAllByText('Codex').length).toBeGreaterThan(0);
    expect(screen.queryByText('2s')).toBeNull();
    expect(document.querySelector('.assistant-response-status-elapsed')).toBeNull();
  });

  it('does not render assistant response status elapsed time while active', () => {
    vi.useFakeTimers();
    const message: ClaudeMessage = {
      type: 'assistant',
      content: '',
      isStreaming: true,
      __assistantResponseStatus: {
        phase: 'thinking',
        providerLabel: 'Codex',
        title: '正在思考',
        description: '正在分析上下文',
        elapsedMs: 2800,
        active: true,
      },
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      currentProvider: 'codex',
    });

    expect(screen.queryByText('2s')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(screen.queryByText('3s')).toBeNull();
    expect(document.querySelector('.assistant-response-status-elapsed')).toBeNull();
    vi.useRealTimers();
  });

  it('renders streaming footer while assistant content is still streaming', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'partial answer',
      isStreaming: true,
      __assistantResponseStatus: {
        phase: 'responding',
        providerLabel: 'Codex',
        title: '正在输出',
        elapsedMs: 2200,
        active: true,
      },
      raw: {
        content: [{ type: 'text', text: 'partial answer' }],
      } as any,
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      currentProvider: 'codex',
    });

    expect(screen.getByText('正在流式输出')).toBeTruthy();
    expect(screen.getByText('2s')).toBeTruthy();
    const streamingFooter = screen.getByText('正在流式输出').closest('.assistant-streaming-footer');
    const messageContent = document.querySelector('.message-content');
    expect(streamingFooter).toBeTruthy();
    expect(messageContent?.contains(streamingFooter)).toBe(true);
    expect(messageContent?.lastElementChild).toBe(streamingFooter);
    expect(screen.queryByText('本次耗时')).toBeNull();
  });

  it('uses loading start time when streaming status elapsed is unavailable', () => {
    vi.useFakeTimers();
    vi.setSystemTime(10_000);
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'partial answer',
      isStreaming: true,
      raw: { content: [{ type: 'text', text: 'partial answer' }] } as any,
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      loadingStartTime: 6_000,
    });

    expect(screen.getByText('正在流式输出')).toBeTruthy();
    expect(screen.getByText('4s')).toBeTruthy();
    vi.useRealTimers();
  });

  it('renders assistant content as thinking tools and output sections', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'done',
      raw: {
        content: [
          { type: 'thinking', thinking: 'analyzing request' },
          { type: 'tool_use', id: 'tool-1', name: 'shell_command', input: { command: 'git status' } },
          { type: 'text', text: 'final answer' },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(document.querySelector('.assistant-sectioned-message-content')).toBeTruthy();
    expect(document.querySelector('.assistant-message-section-thinking')).toBeTruthy();
    expect(document.querySelector('.assistant-message-section-tools')).toBeTruthy();
    expect(document.querySelector('.assistant-message-section-output')).toBeTruthy();
    expect(screen.getByText('思考')).toBeTruthy();
    expect(screen.getByText('工具')).toBeTruthy();
    expect(screen.getByText('输出')).toBeTruthy();
  });

  it('defaults only the last historical thinking block to expanded', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'done',
      raw: {
        content: [
          { type: 'thinking', thinking: 'first thought' },
          { type: 'text', text: 'intermediate answer' },
          { type: 'thinking', thinking: 'latest thought' },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('thinking-0').getAttribute('data-expanded')).toBe('false');
    expect(screen.getByTestId('thinking-2').getAttribute('data-expanded')).toBe('true');
  });

  it('keeps a manually collapsed latest thinking block collapsed across rerenders', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'done',
      raw: {
        content: [{ type: 'thinking', thinking: 'latest thought' }],
      } as any,
    };
    const props: React.ComponentProps<typeof MessageItem> = {
      message,
      messageIndex: 0,
      messageKey: 'message-0',
      isLast: false,
      streamingActive: false,
      isThinking: false,
      t,
      getMessageText,
      getContentBlocks,
      findToolResult,
      extractMarkdownContent,
    };

    const { rerender } = render(<MessageItem {...props} />);

    expect(screen.getByTestId('thinking-0').getAttribute('data-expanded')).toBe('true');
    fireEvent.click(screen.getByTestId('thinking-0'));
    expect(screen.getByTestId('thinking-0').getAttribute('data-expanded')).toBe('false');

    rerender(<MessageItem {...props} streamingActive isLast />);

    expect(screen.getByTestId('thinking-0').getAttribute('data-expanded')).toBe('false');
  });

  it('renders final usage stats with total tokens and duration', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'done',
      durationMs: 123_000,
      raw: {
        turnUsage: {
          input_tokens: 100,
          output_tokens: 40,
        },
        content: [{ type: 'text', text: 'done' }],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByText('输入：')).toBeTruthy();
    expect(screen.getByText('100')).toBeTruthy();
    expect(screen.getByText('输出：')).toBeTruthy();
    expect(screen.getByText('40')).toBeTruthy();
    expect(screen.getByText('总计：')).toBeTruthy();
    expect(screen.getByText('140')).toBeTruthy();
    expect(screen.getByText('2:03')).toBeTruthy();
  });

  it('renders the streaming connect hint using the OpenCode provider label', () => {
    vi.useFakeTimers();
    const message: ClaudeMessage = {
      type: 'assistant',
      content: '',
      isStreaming: true,
      __turnId: 3,
    };

    renderMessageItem(message, {
      isLast: true,
      streamingActive: true,
      currentProvider: 'opencode',
    });

    act(() => {
      vi.advanceTimersByTime(500);
    });

    expect(screen.getByText('OpenCode 已连接')).toBeTruthy();
    vi.useRealTimers();
  });

  it('renders provider errors inside the assistant message card', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'Codex 已连接，正在理解问题',
      raw: {
        content: [
          {
            type: 'text',
            text: 'Codex 已连接，正在理解问题',
          },
          {
            type: 'provider_error',
            provider: 'codex',
            summary: '服务暂时不可用',
            details: 'Codex CLI 请求失败，原因：服务暂时不可用 (503)',
            exitCode: 1,
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('content-block-text')).toBeTruthy();
    expect(screen.getByTestId('content-block-provider_error').textContent).toContain('provider_error:服务暂时不可用');
    expect(document.querySelector('.message.assistant')).toBeTruthy();
    expect(document.querySelector('.message.error')).toBeNull();
  });
});

describe('MessageItem avatar & connect label reflect provider', () => {
  it('labels the assistant avatar with the Codex provider name', () => {
    const message: ClaudeMessage = { type: 'assistant', content: 'hi' };
    renderMessageItem(message, { currentProvider: 'codex' });
    expect(screen.getByText('Codex')).toBeTruthy();
  });

  it('labels the assistant avatar with the OpenCode provider name', () => {
    const message: ClaudeMessage = { type: 'assistant', content: 'hi' };
    renderMessageItem(message, { currentProvider: 'opencode' });
    expect(screen.getByText('OpenCode')).toBeTruthy();
  });

  it('labels the assistant avatar with the Claude provider name by default', () => {
    const message: ClaudeMessage = { type: 'assistant', content: 'hi' };
    renderMessageItem(message);
    expect(screen.getByText('Claude Code')).toBeTruthy();
  });

  it('labels the user avatar with the localized user label', () => {
    const message: ClaudeMessage = { type: 'user', content: 'hi', timestamp: new Date(0).toISOString() };
    renderMessageItem(message);
    expect(screen.getByText('You')).toBeTruthy();
  });
});
