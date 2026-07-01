import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import DualViewSwitcher from './DualViewSwitcher';
import {
  claudeEnvAdapter,
  codexEnvAdapter,
  type ClaudeEnvFormState,
  type CodexEnvFormState,
} from './dualView/adapters';

const renderClaudeForm = (
  state: ClaudeEnvFormState,
  onChange: (n: ClaudeEnvFormState) => void,
) => (
  <input
    aria-label="custom-key"
    value={(state.env.CUSTOM_KEY as string) ?? ''}
    onChange={(e) => onChange({ env: { ...state.env, CUSTOM_KEY: e.target.value } })}
  />
);

describe('DualViewSwitcher', () => {
  it('form 模式渲染表单视图(renderForm)', () => {
    render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: { CUSTOM_KEY: 'val' } }}
        onFormStateChange={vi.fn()}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="form"
        onModeChange={vi.fn()}
      />,
    );
    expect((screen.getByLabelText('custom-key') as HTMLInputElement).value).toBe('val');
  });

  it('切到 json 模式 → JSON 编辑器显示 serialize(formState)', () => {
    render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: { CUSTOM_KEY: 'val' } }}
        onFormStateChange={vi.fn()}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="json"
        onModeChange={vi.fn()}
      />,
    );
    const ta = screen.getByRole('textbox') as HTMLTextAreaElement;
    expect(ta.value).toContain('CUSTOM_KEY');
    expect(ta.value).toContain('val');
  });

  it('json 模式编辑合法 JSON → blur → onFormStateChange 回填解析结果', () => {
    const onFormStateChange = vi.fn();
    render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: {} }}
        onFormStateChange={onFormStateChange}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="json"
        onModeChange={vi.fn()}
      />,
    );
    const ta = screen.getByRole('textbox');
    fireEvent.change(ta, { target: { value: '{\n  "env": {\n    "NEW": "x"\n  }\n}' } });
    fireEvent.blur(ta);
    expect(onFormStateChange).toHaveBeenCalledWith({ env: { NEW: 'x' } });
  });

  it('json 模式编辑非法 JSON → blur → 不回填 + 显示错误(不丢数据,留在 json 模式)', () => {
    const onFormStateChange = vi.fn();
    render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: { KEEP: 'me' } }}
        onFormStateChange={onFormStateChange}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="json"
        onModeChange={vi.fn()}
      />,
    );
    const ta = screen.getByRole('textbox');
    fireEvent.change(ta, { target: { value: '{not json' } });
    fireEvent.blur(ta);
    expect(onFormStateChange).not.toHaveBeenCalled();
    expect(screen.getByText('JSON 语法错误')).toBeTruthy();
    // 草稿保留(不丢数据)
    expect((ta as HTMLTextAreaElement).value).toBe('{not json');
  });

  it('json 非法时点 form tab → 阻止切换(onModeChange 不被调用)', () => {
    const onModeChange = vi.fn();
    const { container } = render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: {} }}
        onFormStateChange={vi.fn()}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="json"
        onModeChange={onModeChange}
      />,
    );
    const ta = screen.getByRole('textbox');
    fireEvent.change(ta, { target: { value: '{bad' } });
    const formTab = container.querySelectorAll('.dvs-tab')[0] as HTMLButtonElement;
    fireEvent.click(formTab);
    expect(onModeChange).not.toHaveBeenCalled();
  });

  it('json 合法时点 form tab → onFormStateChange + onModeChange(form)', () => {
    const onModeChange = vi.fn();
    const onFormStateChange = vi.fn();
    const { container } = render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: {} }}
        onFormStateChange={onFormStateChange}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="json"
        onModeChange={onModeChange}
      />,
    );
    const ta = screen.getByRole('textbox');
    fireEvent.change(ta, { target: { value: '{"env":{"X":"1"}}' } });
    const formTab = container.querySelectorAll('.dvs-tab')[0] as HTMLButtonElement;
    fireEvent.click(formTab);
    expect(onFormStateChange).toHaveBeenCalledWith({ env: { X: '1' } });
    expect(onModeChange).toHaveBeenCalledWith('form');
  });

  it('form tab 切到 json → onModeChange(json)', () => {
    const onModeChange = vi.fn();
    const { container } = render(
      <DualViewSwitcher<ClaudeEnvFormState>
        formState={{ env: {} }}
        onFormStateChange={vi.fn()}
        adapter={claudeEnvAdapter}
        renderForm={renderClaudeForm}
        mode="form"
        onModeChange={onModeChange}
      />,
    );
    const jsonTab = container.querySelectorAll('.dvs-tab')[1] as HTMLButtonElement;
    fireEvent.click(jsonTab);
    expect(onModeChange).toHaveBeenCalledWith('json');
  });

  it('json 合法但 adapter.validate 失败(Codex 重复 key)→ 点 form tab 阻止切换', () => {
    const onModeChange = vi.fn();
    const { container } = render(
      <DualViewSwitcher<CodexEnvFormState>
        formState={{ messageEnvVars: [], mcpEnvVars: [] }}
        onFormStateChange={vi.fn()}
        adapter={codexEnvAdapter}
        renderForm={() => null}
        mode="json"
        onModeChange={onModeChange}
      />,
    );
    const ta = screen.getByRole('textbox');
    fireEvent.change(ta, {
      target: {
        value: '{"messageEnvVars":[{"key":"FOO","value":"1"},{"key":"foo","value":"2"}],"mcpEnvVars":[]}',
      },
    });
    const formTab = container.querySelectorAll('.dvs-tab')[0] as HTMLButtonElement;
    fireEvent.click(formTab);
    expect(onModeChange).not.toHaveBeenCalled();
  });
});
