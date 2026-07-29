import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import JsonEditor from '../../../src/components/shared/JsonEditor';

describe('JsonEditor', () => {
  it('渲染 textarea 并回显 value', () => {
    render(<JsonEditor value={'{"a":1}'} onChange={vi.fn()} />);
    const ta = screen.getByRole('textbox') as HTMLTextAreaElement;
    expect(ta.value).toBe('{"a":1}');
  });

  it('行号数 = value 行数', () => {
    const { container } = render(
      <JsonEditor value={'line1\nline2\nline3'} onChange={vi.fn()} />,
    );
    expect(container.querySelectorAll('.je-lineno').length).toBe(3);
  });

  it('空 value 至少 1 行号', () => {
    const { container } = render(<JsonEditor value={''} onChange={vi.fn()} />);
    expect(container.querySelectorAll('.je-lineno').length).toBe(1);
  });

  it('无 error + highlighted(默认)→ 渲染高亮叠加层', () => {
    const { container } = render(<JsonEditor value={'{"a":1}'} onChange={vi.fn()} />);
    expect(container.querySelector('.je-highlight')).not.toBeNull();
  });

  it('highlighted=false → 不渲染高亮叠加层', () => {
    const { container } = render(
      <JsonEditor value={'{"a":1}'} onChange={vi.fn()} highlighted={false} />,
    );
    expect(container.querySelector('.je-highlight')).toBeNull();
  });

  it('error 非 null → 显示错误文案 + 关闭高亮叠加层(退回纯 textarea 防错位)', () => {
    const { container } = render(
      <JsonEditor value={'{bad'} onChange={vi.fn()} error="JSON 语法错误" />,
    );
    expect(screen.getByText('JSON 语法错误')).toBeTruthy();
    expect(container.querySelector('.je-highlight')).toBeNull();
  });

  it('onChange:输入触发回调', () => {
    const onChange = vi.fn();
    render(<JsonEditor value={'{}'} onChange={onChange} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '{"x":2}' } });
    expect(onChange).toHaveBeenCalledWith('{"x":2}');
  });

  it('Tab 键插入两空格而非切焦点(光标在 0)', () => {
    const onChange = vi.fn();
    render(<JsonEditor value={'{}'} onChange={onChange} />);
    const ta = screen.getByRole('textbox') as HTMLTextAreaElement;
    ta.selectionStart = 0;
    ta.selectionEnd = 0;
    fireEvent.keyDown(ta, { key: 'Tab' });
    expect(onChange).toHaveBeenCalledWith('  {}');
  });

  it('onBlur 回调触发', () => {
    const onBlur = vi.fn();
    render(<JsonEditor value={'{}'} onChange={vi.fn()} onBlur={onBlur} />);
    fireEvent.blur(screen.getByRole('textbox'));
    expect(onBlur).toHaveBeenCalledTimes(1);
  });
});
