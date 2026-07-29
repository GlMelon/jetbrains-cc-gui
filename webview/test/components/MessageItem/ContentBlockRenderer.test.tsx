import { render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { ClaudeContentBlock } from '../../../src/types';
import { ContentBlockRenderer } from '../../../src/components/MessageItem/ContentBlockRenderer';

vi.mock('../MarkdownBlock', () => ({
  default: ({ content }: { content: string }) => <div data-testid="markdown-block">{content}</div>,
}));

const t = ((key: string) => {
  const translations: Record<string, string> = {
    'chat.providerError.title': '本次响应已停止',
    'chat.providerError.details': '错误详情',
    'chat.providerError.provider': '服务',
    'chat.providerError.exitCode': '退出码',
  };
  return translations[key] ?? key;
}) as any;

function getProviderErrorDetailsText(): string | null | undefined {
  return document.querySelector('.provider-error-details pre')?.textContent;
}

function renderBlock(
  block: ClaudeContentBlock,
  findToolResult: ComponentProps<typeof ContentBlockRenderer>['findToolResult'] = () => null,
) {
  return render(
    <ContentBlockRenderer
      block={block}
      blockIndex={0}
      messageIndex={0}
      messageType="assistant"
      isStreaming={false}
      isThinkingExpanded={false}
      isThinking={false}
      isLastMessage={true}
      isLastBlock={true}
      t={t}
      onToggleThinking={() => undefined}
      findToolResult={findToolResult}
    />,
  );
}

describe('ContentBlockRenderer provider_error', () => {
  it('renders provider errors as an inline assistant message block', () => {
    renderBlock({
      type: 'provider_error',
      provider: 'codex',
      summary: '服务暂时不可用',
      details: 'Codex CLI 请求失败，原因：服务暂时不可用 (503)',
      exitCode: 1,
    });

    expect(screen.getByText('本次响应已停止')).toBeTruthy();
    expect(document.querySelector('.provider-error-summary')).toBeNull();
    expect(getProviderErrorDetailsText()).toBe('Codex CLI 请求失败，原因：服务暂时不可用 (503)');
    expect(document.querySelector('.provider-error-block')).toBeTruthy();
  });

  it('renders a non-repeated provider error summary outside details', () => {
    renderBlock({
      type: 'provider_error',
      provider: 'codex',
      summary: '网络请求失败',
      details: 'Codex CLI exited with code 1',
      exitCode: 1,
    });

    expect(screen.getByText('网络请求失败')).toBeTruthy();
    expect(getProviderErrorDetailsText()).toBe('Codex CLI exited with code 1');
  });

  it('keeps the provider error summary visible when no separate details exist', () => {
    renderBlock({
      type: 'provider_error',
      provider: 'codex',
      summary: 'Codex CLI 请求失败',
      exitCode: 1,
    });

    expect(document.querySelector('.provider-error-summary')?.textContent).toBe('Codex CLI 请求失败');
    expect(getProviderErrorDetailsText()).toBe('Codex CLI 请求失败');
  });

  it('keeps repeated provider error details out of the outer summary', () => {
    const repeatedReason = 'Reconnecting... 1/5 (stream disconnected before completion: stream closed before response.completed)';
    const details = `Codex CLI 请求失败，原因：${repeatedReason}\n\nDetails: ${repeatedReason}`;

    renderBlock({
      type: 'provider_error',
      provider: 'codex',
      summary: repeatedReason,
      details,
      exitCode: 1,
    });

    expect(document.querySelector('.provider-error-summary')).toBeNull();
    expect(getProviderErrorDetailsText()).toBe(details);
  });
});

describe('ContentBlockRenderer normalized provider blocks', () => {
  it('renders file_change blocks', () => {
    renderBlock({
      type: 'file_change',
      title: 'File change',
      path: 'src/App.tsx',
      operation: 'modified',
      status: 'completed',
    });

    expect(screen.getByText('File change')).toBeTruthy();
    expect(screen.getByText('src/App.tsx')).toBeTruthy();
    expect(screen.getByText('modified')).toBeTruthy();
  });

  it('renders mcp_tool_call blocks', () => {
    renderBlock({
      type: 'mcp_tool_call',
      title: 'idea_mcp.search_symbols',
      server: 'idea_mcp',
      tool: 'search_symbols',
      input: { q: 'Foo' },
      result: 'ok',
    });

    expect(screen.getByText('idea_mcp.search_symbols')).toBeTruthy();
    expect(screen.getByText('search_symbols')).toBeTruthy();
    expect(screen.getByText(/"q": "Foo"/)).toBeTruthy();
    expect(screen.getByText('ok')).toBeTruthy();
  });

  it('renders web_search blocks', () => {
    renderBlock({
      type: 'web_search',
      title: 'Web search',
      query: 'Codex docs',
      url: 'https://example.com',
    });

    expect(screen.getByText('Web search')).toBeTruthy();
    expect(screen.getByText('Codex docs')).toBeTruthy();
    expect(screen.getByText('https://example.com')).toBeTruthy();
  });

  it('renders todo_list blocks', () => {
    renderBlock({
      type: 'todo_list',
      title: 'Todo list',
      items: [{ text: 'Implement normalizer', status: 'done' }],
    });

    expect(screen.getByText('Todo list')).toBeTruthy();
    expect(screen.getByText('Implement normalizer')).toBeTruthy();
    expect(screen.getByText('done')).toBeTruthy();
  });

  it('renders provider_event blocks without parsing provider raw semantics', () => {
    renderBlock({
      type: 'provider_event',
      provider: 'codex',
      eventType: 'item.completed',
      itemType: 'new_item',
      summary: 'Provider event: new_item',
      details: '{"type":"new_item","summary":"visible"}',
    });

    expect(screen.getByText('Provider event: new_item')).toBeTruthy();
    expect(screen.getByText('item.completed')).toBeTruthy();
    expect(screen.getByText('new_item')).toBeTruthy();
    expect(document.body.textContent).not.toContain('encrypted_content');
  });
});

describe('ContentBlockRenderer tool cards', () => {
  it('renders skill_use blocks as skill cards with truncated title args', () => {
    const longArgs = 'render skill and mcp card previews inside the assistant message card with enough detail to confirm visual grouping and prevent title overflow in compact layouts';

    renderBlock({
      type: 'skill_use',
      name: 'systematic-debugging',
      command: '$systematic-debugging',
      args: longArgs,
      source: 'command-message',
    });

    expect(screen.getByText('Skill: systematic-debugging')).toBeTruthy();
    expect(screen.getByText('skill')).toBeTruthy();
    expect(document.querySelector('.skill-tool-card')).toBeTruthy();
    expect(document.querySelector('.tool-title-summary')?.textContent).toMatch(/\.\.\.$/);
  });

  it('renders MCP tool cards even when input is missing', () => {
    renderBlock(
      {
        type: 'tool_use',
        id: 'mcp_1',
        name: 'mcp__idea_mcp__search_symbols',
      },
      () => ({
        type: 'tool_result',
        tool_use_id: 'mcp_1',
        content: 'found symbols',
      }),
    );

    expect(screen.getByText('MCP: idea_mcp.search_symbols')).toBeTruthy();
    expect(screen.getByText('mcp')).toBeTruthy();
    expect(screen.getByText('idea_mcp')).toBeTruthy();
    expect(document.querySelector('.mcp-tool-card')).toBeTruthy();
  });
});

describe('ContentBlockRenderer thinking section (H2)', () => {
  const thinkingBlock = { type: 'thinking', thinking: '分析用户请求' } as ClaudeContentBlock;

  const renderThinking = (isThinkingExpanded: boolean) =>
    render(
      <ContentBlockRenderer
        block={thinkingBlock}
        blockIndex={0}
        messageIndex={0}
        messageType="assistant"
        isStreaming={false}
        isThinkingExpanded={isThinkingExpanded}
        isThinking={true}
        isLastMessage={true}
        isLastBlock={true}
        t={t}
        onToggleThinking={() => undefined}
        findToolResult={() => null}
      />,
    );

  it('renders content grid container with inner wrapper (collapse contract)', () => {
    // H2: content 为 grid 容器(0fr↔1fr)，inner 为承载 padding/overflow 的 grid item。
    // 二者缺一会让 grid-template-rows 折叠动画失效，作为结构契约守护。
    // happy-dom 不计算 CSS grid 布局，故只验结构，视觉动画靠真实浏览器手动验证。
    renderThinking(false);
    const content = document.querySelector('.thinking-section-content');
    const inner = content?.querySelector('.thinking-section-content-inner');
    expect(content).toBeTruthy();
    expect(inner).toBeTruthy();
    expect(inner?.querySelector('[data-testid="markdown-block"]')).toBeTruthy();
  });

  it('reflects isThinkingExpanded on the .expanded class', () => {
    const { rerender } = renderThinking(false);
    expect(document.querySelector('.thinking-section')?.classList.contains('expanded')).toBe(false);

    rerender(
      <ContentBlockRenderer
        block={thinkingBlock}
        blockIndex={0}
        messageIndex={0}
        messageType="assistant"
        isStreaming={false}
        isThinkingExpanded={true}
        isThinking={true}
        isLastMessage={true}
        isLastBlock={true}
        t={t}
        onToggleThinking={() => undefined}
        findToolResult={() => null}
      />,
    );
    expect(document.querySelector('.thinking-section')?.classList.contains('expanded')).toBe(true);
  });
});
