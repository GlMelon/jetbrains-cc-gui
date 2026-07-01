import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import EnvRecordEditor from './EnvRecordEditor';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

describe('EnvRecordEditor', () => {
  it('只显示自定义 env(过滤保留 key:AUTH_TOKEN/BASE_URL/model mapping)', () => {
    render(
      <EnvRecordEditor
        config={{
          env: {
            ANTHROPIC_AUTH_TOKEN: 'sk',
            ANTHROPIC_BASE_URL: 'http://x',
            ANTHROPIC_DEFAULT_SONNET_MODEL: 'sm',
            CUSTOM_FOO: 'bar',
          },
          model: 'sonnet',
        }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText('env-value-CUSTOM_FOO')).toBeTruthy();
    expect(screen.queryByLabelText('env-value-ANTHROPIC_AUTH_TOKEN')).toBeNull();
    expect(screen.queryByLabelText('env-value-ANTHROPIC_BASE_URL')).toBeNull();
    expect(screen.queryByLabelText('env-value-ANTHROPIC_DEFAULT_SONNET_MODEL')).toBeNull();
  });

  it('value 回显(非字符串值转字符串显示)', () => {
    render(<EnvRecordEditor config={{ env: { CUSTOM_NUM: 42, CUSTOM_FLAG: true } }} onChange={vi.fn()} />);
    expect((screen.getByLabelText('env-value-CUSTOM_NUM') as HTMLInputElement).value).toBe('42');
    expect((screen.getByLabelText('env-value-CUSTOM_FLAG') as HTMLInputElement).value).toBe('true');
  });

  it('改 value → onChange 更新该 key,保留保留 key + 非 env 顶层字段(model 不丢)', () => {
    const onChange = vi.fn();
    render(
      <EnvRecordEditor
        config={{ env: { ANTHROPIC_AUTH_TOKEN: 'sk', CUSTOM_FOO: 'bar' }, model: 'sonnet' }}
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByLabelText('env-value-CUSTOM_FOO'), { target: { value: 'baz' } });
    expect(onChange).toHaveBeenCalledWith({
      env: { ANTHROPIC_AUTH_TOKEN: 'sk', CUSTOM_FOO: 'baz' },
      model: 'sonnet',
    });
  });

  it('删除自定义条目 → onChange 移除该 key(保留 key 不受影响)', () => {
    const onChange = vi.fn();
    render(
      <EnvRecordEditor config={{ env: { ANTHROPIC_AUTH_TOKEN: 'sk', CUSTOM_FOO: 'bar' } }} onChange={onChange} />,
    );
    fireEvent.click(screen.getByLabelText('env-delete-CUSTOM_FOO'));
    expect(onChange).toHaveBeenCalledWith({ env: { ANTHROPIC_AUTH_TOKEN: 'sk' } });
  });

  it('新增 → onChange 添加占位 NEW_KEY(空值)', () => {
    const onChange = vi.fn();
    render(<EnvRecordEditor config={{ env: {} }} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /addCustomEnv/ }));
    expect(onChange).toHaveBeenCalledWith({ env: { NEW_KEY: '' } });
  });

  it('改 key(rename)→ onChange 重命名(删旧加新,值保留)', () => {
    const onChange = vi.fn();
    render(<EnvRecordEditor config={{ env: { OLD: 'v' } }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('env-key-OLD'), { target: { value: 'NEW' } });
    expect(onChange).toHaveBeenCalledWith({ env: { NEW: 'v' } });
  });
});
