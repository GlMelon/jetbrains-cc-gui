import { render, screen } from '@testing-library/react';
import { useRef } from 'react';
import { useTypewriterStream } from './useTypewriterStream';

/**
 * 「流式输出」开关与打字机的配合(总则六对称性的呈现层对应):
 * - 开关开启(enabled=true):逐字节奏由 rAF 驱动,同步渲染期不直出整段;
 * - 开关关闭(enabled=false):后端 TurnPushGate 把 delta 缓冲到轮/段边界一次性下发,
 *   前端必须整段即时显示——不能把大块文本重新"演"成流式;
 * - 中途切换:关闭态 effect 同步全量重绘,不丢字不重复。
 */
function Harness({
  content,
  isStreaming,
  enabled,
}: {
  content: string;
  isStreaming: boolean;
  enabled: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useTypewriterStream(ref, content, isStreaming, enabled);
  return <div ref={ref} data-testid="stream" />;
}

describe('useTypewriterStream streaming toggle', () => {
  it('disabled renders segment-boundary content in full immediately without char-pop spans', () => {
    const { rerender } = render(<Harness content="第一段" isStreaming enabled={false} />);
    const el = screen.getByTestId('stream');
    expect(el.textContent).toBe('第一段');
    // 关闭态整段纯文本,不套 md-char span(无逐字弹入语义)
    expect(el.querySelectorAll('.md-char').length).toBe(0);

    rerender(<Harness content={'第一段\n第二段更多内容'} isStreaming enabled={false} />);
    expect(el.textContent).toContain('第二段更多内容');
    expect(el.querySelectorAll('br').length).toBe(1);
  });

  it('enabled does not dump the full text synchronously (rAF paces the reveal)', () => {
    render(<Harness content="一段比较长的流式文本" isStreaming enabled />);
    const el = screen.getByTestId('stream');
    expect(el.textContent.length).toBeLessThan('一段比较长的流式文本'.length);
  });

  it('toggling off mid-stream completes the display to full content immediately', () => {
    const initial = '第一段';
    const { rerender } = render(<Harness content={initial} isStreaming enabled />);

    const longer = '第一段然后继续增长的内容';
    rerender(<Harness content={longer} isStreaming enabled={false} />);
    const el = screen.getByTestId('stream');
    expect(el.textContent).toBe(longer);
  });
});
