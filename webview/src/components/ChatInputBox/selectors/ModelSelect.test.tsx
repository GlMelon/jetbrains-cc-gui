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
