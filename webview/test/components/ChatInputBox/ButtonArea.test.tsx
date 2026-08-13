import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {STORAGE_KEYS} from '../../../src/types/provider';
import type {ModelInfo} from '../../../src/components/ChatInputBox/types';
import {CLAUDE_ROLE_MODEL_IDS} from '../../../src/components/ChatInputBox/types';

const mocks = vi.hoisted(() => ({
  modelSelectProps: [] as Array<{
    value: string;
    selectedIdentifier?: string;
    onChange: (model: ModelInfo) => void;
    models: ModelInfo[];
    currentProvider: string;
  }>,
  registryModels: [] as Array<ModelInfo & { provider?: string; role?: string }>,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

vi.mock('../../../src/utils/modelRegistry', () => ({
  getModelsForProvider: vi.fn(() => mocks.registryModels),
  getModelRegistrySnapshot: vi.fn(() => ({ items: mocks.registryModels })),
  requestModelRegistry: vi.fn(),
  subscribeModelRegistry: vi.fn(() => () => undefined),
}));

vi.mock('../../../src/components/ChatInputBox/selectors', () => ({
  ConfigSelect: () => null,
  ProviderSelect: () => null,
  ModeSelect: () => null,
  ReasoningSelect: () => null,
  ModelSelect: (props: {
    value: string;
    selectedIdentifier?: string;
    onChange: (model: ModelInfo) => void;
    models: ModelInfo[];
    currentProvider: string;
  }) => {
    mocks.modelSelectProps.push(props);
    const firstModel = props.models[0];
    return (
      <button
        data-testid="model-select"
        type="button"
        onClick={() => props.onChange(firstModel)}
      >
        {firstModel.label}
      </button>
    );
  },
}));

import {ButtonArea} from '../../../src/components/ChatInputBox/ButtonArea';

describe('ButtonArea model mapping', () => {
  // A3:applyModelMapping 读 registryModel.role;registryModels 需含 provider/role。
  const sonnetModel: ModelInfo & { provider: string; role: string } = {
    id: CLAUDE_ROLE_MODEL_IDS.sonnet,
    identifier: 'claude-claude-role-sonnet',
    label: 'Sonnet',
    description: 'Sonnet role',
    contextWindow: 200_000,
    provider: 'claude',
    role: 'sonnet',
  };

  beforeEach(() => {
    localStorage.clear();
    mocks.modelSelectProps = [];
    mocks.registryModels = [sonnetModel];
  });

  it('shows mapped Claude model labels while preserving role model ids for backend resolution', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({sonnet: 'glm5.2'}),
    );
    const onModelSelect = vi.fn();

    render(
      <ButtonArea
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.sonnet}
        onModelSelect={onModelSelect}
      />,
    );

    expect(screen.getByTestId('model-select').textContent).toContain('glm5.2');
    expect(mocks.modelSelectProps.at(-1)?.models[0]).toMatchObject({
      id: CLAUDE_ROLE_MODEL_IDS.sonnet,
      label: 'glm5.2',
    });

    fireEvent.click(screen.getByTestId('model-select'));

    // onChange 现在透传整个 ModelInfo(identifier 精确,不再按裸 id + contextWindow 二次查找)
    expect(onModelSelect).toHaveBeenCalledWith(
      expect.objectContaining({ id: CLAUDE_ROLE_MODEL_IDS.sonnet, identifier: 'claude-claude-role-sonnet' }),
    );
  });

  it('透传 selectedModelIdentifier 到 ModelSelect 用于精确选中', () => {
    render(
      <ButtonArea
        currentProvider="opencode"
        selectedModel="glm-5.2"
        selectedModelIdentifier="opencode-openglm-glm-5.2"
        onModelSelect={vi.fn()}
      />,
    );

    expect(mocks.modelSelectProps.at(-1)?.selectedIdentifier).toBe('opencode-openglm-glm-5.2');
  });
});
