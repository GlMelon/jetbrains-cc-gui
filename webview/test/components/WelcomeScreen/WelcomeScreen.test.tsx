import { render, screen } from '@testing-library/react';
import type { TFunction } from 'i18next';
import { describe, expect, it, vi } from 'vitest';

import { WelcomeScreen } from '../../../src/components/WelcomeScreen/WelcomeScreen';

// 注意:vi.mock 相对路径按测试文件位置(test/)解析,须指向真实源码 src/...,
// 写成 '../BlinkingLogo' 会落到不存在的 test/components/BlinkingLogo 而静默失效。
vi.mock('../../../src/components/BlinkingLogo', () => ({
  BlinkingLogo: () => <div data-testid="blinking-logo" />,
}));

// 注意:vi.mock 相对路径按测试文件位置解析,需指向真实源码 src/components/BlurText,
// 否则不生效(真实 BlurText 会把文字拆成 <span>,致 getByText 整句匹配失败)。
vi.mock('../../../src/components/BlurText', () => ({
  BlurText: ({ text }: { text: string }) => <div>{text}</div>,
}));

vi.mock('../../../src/version/version', () => ({
  APP_VERSION: '0.0.0-test',
}));

describe('WelcomeScreen', () => {
  const t = ((key: string, options?: Record<string, unknown>) => {
    if (key === 'chat.sendMessage') {
      return `给 ${String(options?.provider ?? '')} 发送消息`;
    }
    if (key === 'providers.codex.label') {
      return 'Codex';
    }
    if (key === 'providers.claude.label') {
      return 'Claude Code';
    }
    return key;
  }) as unknown as TFunction;

  it('uses the translated Codex provider label in the welcome copy', () => {
    render(
      <WelcomeScreen
        currentProvider="codex"
        t={t}
        onProviderChange={vi.fn()}
      />,
    );

    expect(screen.getByText('给 Codex 发送消息')).toBeTruthy();
    expect(screen.queryByText('给 Codex Cli 发送消息')).toBeNull();
  });

  it('keeps the Claude provider label in the welcome copy', () => {
    render(
      <WelcomeScreen
        currentProvider="claude"
        t={t}
        onProviderChange={vi.fn()}
      />,
    );

    expect(screen.getByText('给 Claude Code 发送消息')).toBeTruthy();
  });
});
