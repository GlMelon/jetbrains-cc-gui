// @vitest-environment happy-dom

// 状态卡文案 i18n 接管验证:真实 webview 环境(react-i18next 已初始化)下,标题/描述
// 按 chat.responsePhase.* 解析而非直接渲染后端下发文本;后端文本仅作缺 key fallback。
// 注意:MessageItem.test.tsx 无 i18n 初始化,走的是 fallback 路径,与本文件互补。

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { AssistantResponseStatusPayload as Payload } from '../../../src/types';

const translations: Record<string, string> = {
  'chat.responsePhase.connecting.title': '正在启动运行时',
  'chat.responsePhase.connecting.description': '正在启动 AI CLI 进程',
  'chat.responsePhase.understanding.title': '正在等待模型响应',
  'chat.responsePhase.apiRetry.description': '重试（{{attempt}}/{{max}}）',
  'chat.responsePhase.cancelled.description': '用户已取消请求',
};

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    // 模拟已初始化 i18n:命中返回译文(含 {{}} 插值),未命中返回 defaultValue
    t: (key: string, opts?: { defaultValue?: string } & Record<string, unknown>) => {
      let value = translations[key] ?? opts?.defaultValue ?? key;
      if (opts) {
        Object.entries(opts).forEach(([k, v]) => {
          if (k !== 'defaultValue') value = String(value).split(`{{${k}}}`).join(String(v));
        });
      }
      return value;
    },
  }),
}));

import { AssistantResponseStatus } from '../../../src/components/MessageItem/AssistantResponseStatus';

const base = { providerLabel: 'AI', elapsedMs: 0, active: true };

describe('AssistantResponseStatus i18n — 前端接管文案', () => {
  it('connecting 阶段标题与描述均按 i18n 解析,忽略后端下发英文文本', () => {
    const payload: Payload = {
      ...base,
      phase: 'connecting',
      title: 'Starting runtime',
      description: 'Launching the AI CLI process',
      descriptionKey: 'connecting',
    } as Payload;
    render(<AssistantResponseStatus payload={payload} />);
    expect(screen.getByText('正在启动运行时')).toBeTruthy();
    expect(screen.getByText('正在启动 AI CLI 进程')).toBeTruthy();
    expect(screen.queryByText('Starting runtime')).toBeNull();
    expect(screen.queryByText('Launching the AI CLI process')).toBeNull();
  });

  it('标题 · 描述同行渲染(单行布局)', () => {
    const payload: Payload = {
      ...base,
      phase: 'connecting',
      title: 'Starting runtime',
      description: 'Launching the AI CLI process',
      descriptionKey: 'connecting',
    } as Payload;
    const { container } = render(<AssistantResponseStatus payload={payload} />);
    const row = container.querySelector('.ars-title-row');
    expect(row).toBeTruthy();
    expect(row!.querySelector('.ars-text')).toBeTruthy();
    expect(row!.querySelector('.ars-desc')?.textContent).toBe('正在启动 AI CLI 进程');
  });

  it('api_retry 用 attempt/maxRetries 字段格式化重试文案', () => {
    const payload: Payload = {
      ...base,
      phase: 'api_retry',
      title: 'Waiting for model',
      description: 'retrying (2/5)',
      descriptionKey: 'apiRetry',
      attempt: 2,
      maxRetries: 5,
    } as Payload;
    render(<AssistantResponseStatus payload={payload} />);
    expect(screen.getByText('重试（2/5）')).toBeTruthy();
  });

  it('cancelled descriptionKey 覆盖 error 默认描述', () => {
    const payload: Payload = {
      ...base,
      phase: 'error',
      title: 'Response interrupted',
      description: 'Request cancelled by user',
      descriptionKey: 'cancelled',
    } as Payload;
    render(<AssistantResponseStatus payload={payload} />);
    expect(screen.getByText('用户已取消请求')).toBeTruthy();
  });

  it('缺 key(未知 phase)时回退后端下发文本', () => {
    const payload: Payload = {
      ...base,
      phase: 'future_phase',
      title: 'Backend title',
      description: 'Backend description',
    } as Payload;
    render(<AssistantResponseStatus payload={payload} />);
    expect(screen.getByText('Backend title')).toBeTruthy();
    expect(screen.getByText('Backend description')).toBeTruthy();
  });
});
