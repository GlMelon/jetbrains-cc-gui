import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
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
  ContentBlockRenderer: ({ block }: { block: ClaudeContentBlock }) => (
    <div data-testid={`content-block-${block.type}`}>
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
    'chat.totalDuration': '本次耗时',
    'chat.waitingTimedOutDuration': '等待超时',
    'chat.usageStats.duration': '本次耗时',
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
