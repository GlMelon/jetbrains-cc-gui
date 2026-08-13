import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ModelSelect} from '../../../../src/components/ChatInputBox/selectors/ModelSelect';
import type {ModelInfo} from '../../../../src/components/ChatInputBox/types';
import {STORAGE_KEYS} from '../../../../src/types/provider';
import {__setModelRegistryForTests, resetModelRegistryForTests} from '../../../../src/utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.model ?? key,
  }),
}));

describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-role-sonnet',
    identifier: 'claude-claude-role-sonnet',
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
          identifier: 'claude-claude-role-sonnet',
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
        identifier: 'claude-mimo-v2.5',
        label: 'mimo-v2.5',
        description: 'MiMo Sonnet',
      },
      {
        id: 'glm-5.2',
        identifier: 'claude-glm-5.2',
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
    expect(selectedOptions[0].querySelectorAll('.check-mark')).toHaveLength(1);
  });

  it('自定义 Claude 模型即使 role 命中内置角色,也不被全局 role 映射覆盖 label', () => {
    // 复现:自定义 mimo-v2.5 添加时被设 role=sonnet(与内置 claude-role-sonnet 同角色),
    // 且有自身 actualModel。全局映射 sonnet→glm-5.2 仅应作用于内置 sonnet,
    // 不能把 mimo 的 label 也覆盖成 glm-5.2(否则两个选项显示同名,用户无法区分/选择自定义模型)。
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'claude-claude-role-sonnet',
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
          identifier: 'claude-mimo-v2.5',
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
      { id: 'claude-role-sonnet', identifier: 'claude-claude-role-sonnet', label: 'Sonnet', description: 'Sonnet role', contextWindow: 200_000 },
      { id: 'mimo-v2.5', identifier: 'claude-mimo-v2.5', label: 'mimo-v2.5', description: 'MiMo Sonnet', contextWindow: 200_000 },
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

  it('相同模型 ID 不同来源按 identifier 精确勾选唯一项(核心回归)', () => {
    // 复现:OpenCode 配置存在同 id(glm-5.2)但不同 source(maoyulin / Zhipu GLM)的模型,
    // 旧逻辑按裸 id 判断选中会把两项同时勾上。改为前端只认后端下发的 identifier,
    // identifier 精确匹配时仅勾选目标项。
    const models: ModelInfo[] = [
      { id: 'glm-5.2', identifier: 'opencode-maoyulin-glm-5.2', label: 'GLM 5.2', description: 'maoyulin source', contextWindow: 128_000 },
      { id: 'glm-5.2', identifier: 'opencode-openglm-glm-5.2', label: 'GLM 5.2', description: 'Zhipu GLM source', contextWindow: 200_000 },
    ];

    const onChange = vi.fn();
    const { container } = render(
      <ModelSelect
        value="glm-5.2"
        selectedIdentifier="opencode-openglm-glm-5.2"
        onChange={onChange}
        models={models}
        currentProvider="opencode"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const selectedOptions = container.querySelectorAll('.selector-option.selected');
    expect(selectedOptions).toHaveLength(1);
    expect(selectedOptions[0].textContent).toContain('Zhipu GLM source');
    expect(selectedOptions[0].textContent).not.toContain('maoyulin source');
    // 仅一个 check mark(旧 bug 因裸 id 相同会渲染两个)
    expect(container.querySelectorAll('.check-mark')).toHaveLength(1);

    // 点击目标项,onChange 收到完整 ModelInfo(identifier 精确,前端不拆解)
    fireEvent.click(selectedOptions[0]);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'glm-5.2', identifier: 'opencode-openglm-glm-5.2' }),
    );
  });

  it('未传 selectedIdentifier 时按裸 ID 回退匹配(旧状态兼容)', () => {
    const models: ModelInfo[] = [
      { id: 'glm-5.2', identifier: 'opencode-maoyulin-glm-5.2', label: 'GLM 5.2', description: 'maoyulin', contextWindow: 128_000 },
      { id: 'glm-5.2', identifier: 'opencode-openglm-glm-5.2', label: 'GLM 5.2', description: 'Zhipu', contextWindow: 200_000 },
    ];

    const { container } = render(
      <ModelSelect
        value="glm-5.2"
        onChange={vi.fn()}
        models={models}
        currentProvider="opencode"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    // 无 identifier 时按裸 id 匹配(find 返回第一个),仅一项选中,不崩溃。
    expect(container.querySelectorAll('.selector-option.selected')).toHaveLength(1);
  });
});
