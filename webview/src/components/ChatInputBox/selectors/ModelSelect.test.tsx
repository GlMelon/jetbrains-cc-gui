import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ModelSelect} from './ModelSelect';
import type {ModelInfo} from '../types';
import {STORAGE_KEYS} from '../../../types/provider';
import {__setModelRegistryForTests, resetModelRegistryForTests} from '../../../utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.model ?? key,
  }),
}));

describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-role-sonnet',
    label: 'Sonnet',
    description: 'Sonnet role',
    contextWindow: 200_000,
  };

  beforeEach(() => {
    localStorage.clear();
    // D5:角色解析已收口到 registry 的 role 字段(与 ButtonArea/生产同源),
    // 内置 Claude 模型须先经后端下发入 registry 才能命中映射。测试种子 sonnet 内置项。
    resetModelRegistryForTests();
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          contextWindow: 200_000,
          supports1MContext: false,
          enabled: true,
          readOnly: true,
        },
      ],
    });
  });

  it('rerender 后应读取最新的 Claude 模型映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-4' }),
    );

    const { rerender } = render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4');

    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-5' }),
    );

    rerender(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-5');
  });

  it('没有具体映射时应回退到全局 main 映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ main: 'glm-4.7' }),
    );

    render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4.7');
  });

  it('Claude 自定义模型选中态按真实模型 ID 精确匹配', () => {
    const models: ModelInfo[] = [
      {
        id: 'mimo-v2.5',
        label: 'mimo-v2.5',
        description: 'MiMo Sonnet',
      },
      {
        id: 'glm-5.2',
        label: 'glm-5.2',
        description: 'GLM Sonnet',
      },
    ];

    const { container } = render(
      <ModelSelect
        value="mimo-v2.5"
        onChange={vi.fn()}
        models={models}
        currentProvider="claude"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const selectedOptions = container.querySelectorAll('.selector-option.selected');
    expect(selectedOptions).toHaveLength(1);
    expect(selectedOptions[0].textContent).toContain('mimo-v2.5');
    expect(selectedOptions[0].textContent).not.toContain('glm-5.2');
    expect(container.querySelectorAll('.codicon-check')).toHaveLength(1);
  });

  it('自定义 Claude 模型即使 role 命中内置角色,也不被全局 role 映射覆盖 label', () => {
    // 复现:自定义 mimo-v2.5 添加时被设 role=sonnet(与内置 claude-role-sonnet 同角色),
    // 且有自身 actualModel。全局映射 sonnet→glm-5.2 仅应作用于内置 sonnet,
    // 不能把 mimo 的 label 也覆盖成 glm-5.2(否则两个选项显示同名,用户无法区分/选择自定义模型)。
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          actualModel: 'glm-5.2',
          contextWindow: 200_000,
          supports1MContext: false,
          enabled: true,
          readOnly: true,
        },
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'sonnet',
          label: 'mimo-v2.5',
          actualModel: 'mimo-v2.5',
          contextWindow: 200_000,
          supports1MContext: false,
          enabled: true,
          readOnly: false,
        },
      ],
    });
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-5.2' }),
    );

    const models: ModelInfo[] = [
      { id: 'claude-role-sonnet', label: 'Sonnet', description: 'Sonnet role', contextWindow: 200_000 },
      { id: 'mimo-v2.5', label: 'mimo-v2.5', description: 'MiMo Sonnet', contextWindow: 200_000 },
    ];

    const { container } = render(
      <ModelSelect
        value="claude-role-sonnet"
        onChange={vi.fn()}
        models={models}
        currentProvider="claude"
      />,
    );
    fireEvent.click(screen.getByRole('button'));

    const options = container.querySelectorAll('.selector-option');
    // 用稳定的 description 定位 mimo 选项(getModelLabel 不覆盖 description)
    const mimoOption = Array.from(options).find((o) => o.textContent?.includes('MiMo Sonnet'));
    expect(mimoOption).toBeTruthy();

    const labelTexts = Array.from(mimoOption!.querySelectorAll('span')).map((s) => s.textContent ?? '');
    expect(labelTexts).toContain('mimo-v2.5');   // label 必须显示自身名
    expect(labelTexts).not.toContain('glm-5.2'); // 不能被 role 映射覆盖成内置 sonnet 的映射名
  });

  it('models 为空时不崩溃,渲染未配置占位', () => {
    render(
      <ModelSelect
        value=""
        onChange={vi.fn()}
        models={[]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    expect(button).toHaveProperty('disabled', true);
    expect(button.textContent).toContain('chat.noModelConfigured');
  });
});
