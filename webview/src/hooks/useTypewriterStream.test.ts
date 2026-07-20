import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useTypewriterStream } from './useTypewriterStream';

/**
 * 逐字打字机 hook 单测 —— 聚焦用户最关心的「不卡顿 / 不积压」。
 *
 * rAF mock 采用异步语义(useFakeTimers + setTimeout 包装),与真实浏览器一致,
 * 避开同步 rAF 陷阱(见 [[webview-vitest-raf-mock-trap]])。
 * frame() 用 runOnlyPendingTimers 逐帧推进 —— 只跑当前 pending 的 rAF 回调,
 * 不递归跑 tick 内新调度的下一帧,故不会无限循环。
 */
describe('useTypewriterStream', () => {
  let container: HTMLDivElement;
  let stableRef: { current: HTMLDivElement | null };
  let rafSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // toFake 仅含 setTimeout/clearTimeout:rAF 系列由我们手动 stub(基于 fake setTimeout)。
    // 若让 useFakeTimers 默认接管 cancelAnimationFrame,它会把 rAF 返回的 setTimeout-id
    // 当作 rAF-timer 清理,触发 "timer created with setTimeout but cleared with
    // cancelAnimationFrame" 的 cross-clear 报错(hook cleanup 必然 cancelAnimationFrame)。
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    rafSpy = vi.fn((cb: FrameRequestCallback) =>
      setTimeout(() => cb(0), 0) as unknown as number,
    );
    vi.stubGlobal('requestAnimationFrame', rafSpy);
    vi.stubGlobal('cancelAnimationFrame', (id: unknown) =>
      clearTimeout(id as unknown as ReturnType<typeof setTimeout>),
    );
    container = document.createElement('div');
    stableRef = { current: container };
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  const frame = () => vi.runOnlyPendingTimers();

  const mount = (content: string, isStreaming: boolean) =>
    renderHook(
      (props: { content: string; isStreaming: boolean }) =>
        useTypewriterStream(stableRef, props.content, props.isStreaming),
      { initialProps: { content, isStreaming } },
    );

  it('逐字追加:小 backlog 每帧吐 1 字,每个字符套 .md-char span', () => {
    mount('Hello', true);
    expect(container.querySelectorAll('.md-char')).toHaveLength(0);

    frame(); // 'H'
    expect(container.querySelectorAll('.md-char')).toHaveLength(1);
    frame(); // 'e'
    expect(container.querySelectorAll('.md-char')).toHaveLength(2);
    frame(); // 'l'
    frame(); // 'l'
    frame(); // 'o'
    expect(container.querySelectorAll('.md-char')).toHaveLength(5);
    expect(container.textContent).toBe('Hello');
  });

  it('失控保护:backlog > 600 时一帧全量吐完,绝不让队列积压', () => {
    const big = 'a'.repeat(700);
    mount(big, true);
    frame(); // 第一帧 backlog=700>600 → advance=700,一次性全量吐出
    expect(container.textContent).toBe(big);
    expect(container.textContent).toHaveLength(700);
  });

  it('追赶模式:120 < backlog ≤ 600 每帧吐 35%,快速消解后端突发', () => {
    mount('a'.repeat(200), true);
    frame(); // backlog=200 → advance=round(200*0.35)=70
    expect(container.textContent).toHaveLength(70);
    frame(); // backlog=130 → advance=round(130*0.35)=46
    expect(container.textContent).toHaveLength(70 + 46);
  });

  it('POP_LIMIT 封顶:超长回复 span 总数硬性封顶,超出部分降级纯文本节点', () => {
    // 失控保护一次性 from=0 吐 5000 字:前 POP_LIMIT(1500) 个用 span,
    // 后 3500 个降级 text node,span 总数封顶 1500,防止撑爆 DOM。
    // POP_LIMIT 权威值在 useTypewriterStream.ts(=1500,性能封顶),本测试同步之。
    mount('a'.repeat(5000), true);
    frame();
    expect(container.querySelectorAll('.md-char')).toHaveLength(1500);
    expect(container.textContent).toHaveLength(5000);
  });

  it('换行渲染为 <br>,不进入 span', () => {
    mount('a\nb', true);
    frame(); // 'a'
    frame(); // '\n' → <br>
    frame(); // 'b'
    expect(container.querySelectorAll('br')).toHaveLength(1);
    expect(container.querySelectorAll('.md-char')).toHaveLength(2);
    expect(container.textContent).toBe('ab'); // <br> 无 textContent
  });

  it('流式结束且全部吐完后停止 rAF,不再空转', () => {
    const { rerender } = mount('Hi', true);
    frame(); // 'H'
    frame(); // 'i'
    expect(container.textContent).toBe('Hi');

    rerender({ content: 'Hi', isStreaming: false });
    const callsBefore = rafSpy.mock.calls.length;
    frame(); frame(); frame(); // 推进多帧
    // !streaming && shown>=full → 停止 return,不再调度新 rAF
    expect(rafSpy.mock.calls.length).toBe(callsBefore);
  });

  it('消息切换:content 前缀与已显示部分不一致时清空重置', () => {
    const { rerender } = mount('ABC', true);
    frame(); // 'A'
    frame(); // 'B'
    expect(container.textContent).toBe('AB');

    // 切到新消息(不以 'AB' 开头)→ 清空容器、计数归零
    rerender({ content: 'XYZ', isStreaming: true });
    expect(container.textContent).toBe('');

    frame(); frame(); frame(); // 从 'X' 重新逐字
    expect(container.textContent).toBe('XYZ');
  });

  it('流式增量:content 逐步增长时,已显示字符保留,新字符继续逐字追加', () => {
    const { rerender } = mount('Hel', true);
    frame(); frame(); frame(); // H, e, l
    expect(container.textContent).toBe('Hel');

    // 后端增量推送 +lo:新 content 以已显示 'Hel' 为前缀 → 不清空,继续追加
    rerender({ content: 'Hello', isStreaming: true });
    frame(); // 'l'
    frame(); // 'o'
    expect(container.textContent).toBe('Hello');
  });
});
