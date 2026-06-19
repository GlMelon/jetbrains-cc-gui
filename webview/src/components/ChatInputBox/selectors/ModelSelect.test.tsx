import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ModelSelect} from './ModelSelect';
import type {ModelInfo} from '../types';
import {
  CLAUDE_MODELS,
  CLAUDE_ROLE_MODEL_IDS,
  CODEX_MODELS,
  modelSupports1MContext,
  normalizeClaudeModelId,
} from '../types';
import {STORAGE_KEYS} from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.model ?? key,
  }),
}));

describe('modelSupports1MContext', () => {
  it('returns true for Claude non-Haiku role models', () => {
    expect(modelSupports1MContext(CLAUDE_ROLE_MODEL_IDS.sonnet)).toBe(true);
    expect(modelSupports1MContext(CLAUDE_ROLE_MODEL_IDS.opus)).toBe(true);
    expect(modelSupports1MContext(CLAUDE_ROLE_MODEL_IDS.fable)).toBe(true);
  });

  it('returns false for Haiku', () => {
    expect(modelSupports1MContext(CLAUDE_ROLE_MODEL_IDS.haiku)).toBe(false);
  });

  it('returns false for unknown models without contextWindow', () => {
    expect(modelSupports1MContext('qwen3-max')).toBe(false);
    expect(modelSupports1MContext('deepseek-v4-pro')).toBe(false);
  });

  it('returns true for model with contextWindow >= 1M', () => {
    const models: ModelInfo[] = [{id: 'qwen3-max', label: 'Qwen3', contextWindow: 1_000_000}];
    expect(modelSupports1MContext('qwen3-max', models)).toBe(true);
  });

  it('returns false for model with contextWindow < 1M', () => {
    const models: ModelInfo[] = [{id: 'qwen3-max', label: 'Qwen3', contextWindow: 200_000}];
    expect(modelSupports1MContext('qwen3-max', models)).toBe(false);
  });

  it('strips [1m] suffix before lookup', () => {
    const models: ModelInfo[] = [{id: 'custom-model', label: 'Custom', contextWindow: 1_000_000}];
    expect(modelSupports1MContext('custom-model[1m]', models)).toBe(true);
  });

  it('returns false for null/undefined', () => {
    expect(modelSupports1MContext(null)).toBe(false);
    expect(modelSupports1MContext(undefined)).toBe(false);
    expect(modelSupports1MContext('')).toBe(false);
  });
});

describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-role-sonnet',
    label: 'Sonnet',
    description: 'Sonnet role',
    contextWindow: 200_000,
  };

  beforeEach(() => {
    localStorage.clear();
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

  it('Claude 内置模型列表只暴露稳定角色 ID', () => {
    const ids = CLAUDE_MODELS.map((model) => model.id);
    expect(ids).toEqual([
      CLAUDE_ROLE_MODEL_IDS.sonnet,
      CLAUDE_ROLE_MODEL_IDS.opus,
      CLAUDE_ROLE_MODEL_IDS.fable,
      CLAUDE_ROLE_MODEL_IDS.haiku,
    ]);
  });

  it('旧 Claude 具体模型 ID 不再兼容，回退到默认 Sonnet 角色', () => {
    expect(normalizeClaudeModelId('claude-sonnet-4-6')).toBe(CLAUDE_ROLE_MODEL_IDS.sonnet);
    expect(normalizeClaudeModelId('claude-opus-4-8')).toBe(CLAUDE_ROLE_MODEL_IDS.sonnet);
    expect(normalizeClaudeModelId('claude-fable-5')).toBe(CLAUDE_ROLE_MODEL_IDS.sonnet);
    expect(normalizeClaudeModelId('claude-haiku-4-5')).toBe(CLAUDE_ROLE_MODEL_IDS.sonnet);
    expect(normalizeClaudeModelId('glm5.2')).toBe(CLAUDE_ROLE_MODEL_IDS.sonnet);
  });

  it('Codex 不再内置具体 GPT 版本清单', () => {
    expect(CODEX_MODELS).toEqual([]);
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
