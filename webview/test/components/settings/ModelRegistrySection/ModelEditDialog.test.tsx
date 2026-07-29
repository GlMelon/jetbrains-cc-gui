import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ModelEditDialog from '../../../../src/components/settings/ModelRegistrySection/ModelEditDialog';
import { ONE_MILLION_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW } from '../../../../src/components/ChatInputBox/types';
import type { ModelRegistryItem } from '../../../../src/utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (_key: string, fallback?: string) => fallback ?? _key }),
}));

const baseEditing = (overrides: Partial<ModelRegistryItem> = {}): ModelRegistryItem => ({
  provider: 'claude',
  id: '',
  role: 'sonnet',
  label: '',
  actualModel: '',
  description: '',
  contextWindow: DEFAULT_CONTEXT_WINDOW,
  supports1MContext: false,
  enabled: true,
  readOnly: false,
  ...overrides,
});

const renderDialog = (overrides: Record<string, unknown> = {}) =>
  render(
    <ModelEditDialog
      isOpen
      editing={baseEditing()}
      editingOriginalKey={null}
      onClose={vi.fn()}
      onSubmit={vi.fn()}
      {...overrides}
    />,
  );

// 新增态确认按钮文案为"确定";编辑态相关用例不调用此 helper
const confirmButton = () => screen.getByText('确定').closest('button') as HTMLButtonElement;

describe('ModelEditDialog', () => {
  it('新增态标题为"新增模型",确认按钮文案为"确定"', () => {
    renderDialog();
    expect(screen.getByText('新增模型')).toBeTruthy();
    expect(screen.getByText('确定')).toBeTruthy();
  });

  it('编辑态标题为"编辑模型",确认按钮文案为"保存修改"', () => {
    renderDialog({ editingOriginalKey: 'claude:x' });
    expect(screen.getByText('编辑模型')).toBeTruthy();
    expect(screen.getByText('保存修改')).toBeTruthy();
  });

  it('claude:显示角色与实际请求模型,无模型 ID;缺实际模型时确认禁用', () => {
    renderDialog();
    expect(screen.getByText('角色')).toBeTruthy();
    expect(screen.getByPlaceholderText('例如 claude-sonnet-4-6')).toBeTruthy();
    expect(screen.queryByPlaceholderText('例如 gpt-5.5')).toBeNull();
    expect(confirmButton().disabled).toBe(true);
  });

  it('codex:显示模型 ID,无角色/无实际请求模型', () => {
    renderDialog({ editing: baseEditing({ provider: 'codex', role: undefined, actualModel: undefined }) });
    expect(screen.getByPlaceholderText('例如 gpt-5.5')).toBeTruthy();
    expect(screen.queryByText('角色')).toBeNull();
    expect(screen.queryByPlaceholderText('例如 claude-sonnet-4-6')).toBeNull();
  });

  it('opencode:同时显示模型 ID 与实际请求模型,无角色', () => {
    renderDialog({ editing: baseEditing({ provider: 'opencode', role: undefined }) });
    expect(screen.getByPlaceholderText('例如 gpt-5.5')).toBeTruthy();
    expect(screen.getByPlaceholderText('例如 claude-sonnet-4-6')).toBeTruthy();
    expect(screen.queryByText('角色')).toBeNull();
  });

  it('切换 provider 联动字段显隐(claude→codex)', () => {
    renderDialog();
    // 初始 claude,预览 provider=claude,故 'codex' 仅匹配分段按钮,唯一
    fireEvent.click(screen.getByText('codex'));
    expect(screen.queryByText('角色')).toBeNull();
    expect(screen.getByPlaceholderText('例如 gpt-5.5')).toBeTruthy();
  });

  it('提交 claude 规范化:id 与 label 回退为 actualModel,role 保留', () => {
    const onSubmit = vi.fn();
    renderDialog({ editing: baseEditing({ actualModel: '  claude-sonnet-4-6  ' }), onSubmit });
    fireEvent.click(confirmButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    const [item, key] = onSubmit.mock.calls[0];
    expect(item.id).toBe('claude-sonnet-4-6');
    expect(item.actualModel).toBe('claude-sonnet-4-6');
    expect(item.label).toBe('claude-sonnet-4-6');
    expect(item.role).toBe('sonnet');
    expect(key).toBeNull();
  });

  it('提交 codex 规范化:id 来自输入,actualModel 置空,label 用用户输入', () => {
    const onSubmit = vi.fn();
    renderDialog({
      editing: baseEditing({ provider: 'codex', id: 'gpt-5.5', role: undefined, actualModel: undefined, label: 'My GPT' }),
      onSubmit,
    });
    fireEvent.click(confirmButton());
    const [item] = onSubmit.mock.calls[0];
    expect(item.id).toBe('gpt-5.5');
    expect(item.actualModel).toBeUndefined();
    expect(item.label).toBe('My GPT');
    expect(item.role).toBeUndefined();
  });

  it('预览:主键空时显示占位,填入 actualModel 后同步', () => {
    renderDialog();
    expect(screen.getByText('(未填写)')).toBeTruthy();
    fireEvent.change(screen.getByPlaceholderText('例如 claude-sonnet-4-6'), { target: { value: 'claude-sonnet-4-6' } });
    expect(screen.getByText('claude-sonnet-4-6')).toBeTruthy();
  });

  it('预览:label 非空时优先于 actualModel 显示', () => {
    renderDialog({ editing: baseEditing({ actualModel: 'claude-sonnet-4-6', label: '我的模型' }) });
    expect(screen.getByText('我的模型')).toBeTruthy();
  });

  it('Switch:开启支持 1M 联动 contextWindow=1M 并提交', () => {
    const onSubmit = vi.fn();
    renderDialog({
      editing: baseEditing({ provider: 'codex', id: 'gpt-5.5', role: undefined, actualModel: undefined }),
      onSubmit,
    });
    const switches = screen.getAllByRole('switch');
    expect(switches).toHaveLength(2);
    fireEvent.click(switches[1]); // 第二个 = 支持 1M
    fireEvent.click(confirmButton());
    const [item] = onSubmit.mock.calls[0];
    expect(item.supports1MContext).toBe(true);
    expect(item.contextWindow).toBe(ONE_MILLION_CONTEXT_WINDOW);
  });

  it('Switch:关闭启用后提交 enabled=false', () => {
    const onSubmit = vi.fn();
    renderDialog({
      editing: baseEditing({ provider: 'codex', id: 'gpt-5.5', role: undefined, actualModel: undefined }),
      onSubmit,
    });
    const switches = screen.getAllByRole('switch');
    fireEvent.click(switches[0]); // 第一个 = 启用,初始 true→false
    fireEvent.click(confirmButton());
    const [item] = onSubmit.mock.calls[0];
    expect(item.enabled).toBe(false);
  });

  it('codex 缺 id 时确认禁用,填入后启用', () => {
    renderDialog({ editing: baseEditing({ provider: 'codex', id: '', role: undefined, actualModel: undefined }) });
    expect(confirmButton().disabled).toBe(true);
    fireEvent.change(screen.getByPlaceholderText('例如 gpt-5.5'), { target: { value: 'gpt-5.5' } });
    expect(confirmButton().disabled).toBe(false);
  });

  it('isOpen=false 时不渲染', () => {
    render(
      <ModelEditDialog isOpen={false} editing={null} editingOriginalKey={null} onClose={vi.fn()} onSubmit={vi.fn()} />,
    );
    expect(screen.queryByText('新增模型')).toBeNull();
  });

  it('打开时回填 editing 数据', () => {
    renderDialog({
      editing: baseEditing({ provider: 'claude', actualModel: 'claude-opus-4-8', label: 'Opus', role: 'opus' }),
    });
    expect((screen.getByPlaceholderText('例如 claude-sonnet-4-6') as HTMLInputElement).value).toBe('claude-opus-4-8');
    expect((screen.getByPlaceholderText('为空时使用实际请求模型') as HTMLInputElement).value).toBe('Opus');
  });
});
