import { readFileSync } from 'node:fs';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AiFeatureProviderModelPanel from '../../../../src/components/settings/AiFeatureProviderModelPanel/index';
import type { CommitAiConfig } from '../../../../src/types/aiFeatureConfig';

const panelStyles = readFileSync(
  'src/components/settings/AiFeatureProviderModelPanel/style.module.less',
  'utf8'
);

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.provider
      ? `${key}:${options.provider}`
      : key,
  }),
}));

vi.mock('../../../utils/modelRegistry', () => ({
  getModelsForProvider: vi.fn((provider: string) => provider === 'codex'
    ? [
      { id: 'gpt-5.4', label: 'GPT-5.4', contextWindow: 1_000_000 },
      { id: 'gpt-5.5', label: 'GPT-5.5', contextWindow: 1_000_000 },
    ]
    : []),
  subscribeModelRegistry: vi.fn(() => () => {}),
}));

describe('AiFeatureProviderModelPanel', () => {
  const config: CommitAiConfig = {
    provider: null,
    effectiveProvider: 'codex',
    resolutionSource: 'auto',
    models: {
      claude: 'claude-sonnet-4-6',
      codex: 'gpt-5.5',
      opencode: '',
    },
    availability: {
      claude: true,
      codex: true,
      opencode: true,
    },
  };

  it('renders provider select, model select, and status hint', () => {
    render(
      <AiFeatureProviderModelPanel
        config={config}
        settingsKeyPrefix="settings.commit.providerModel"
        providerKeyPrefix="settings.basic.promptEnhancer.provider"
        onProviderChange={vi.fn()}
        onModelChange={vi.fn()}
      />
    );

    expect(screen.getByText('settings.commit.providerModel.currentProviderAuto:settings.basic.promptEnhancer.provider.codex')).toBeTruthy();
    expect(screen.getByTestId('provider-select-icon')).toBeTruthy();
    expect(screen.getByTestId('ai-feature-actions-row')).toBeTruthy();
    expect(screen.getByTestId('ai-feature-status-hint')).toBeTruthy();
    expect(screen.getAllByRole('combobox')).toHaveLength(2);
  });

  it('keeps both rows compact with ellipsis instead of wrapping', () => {
    expect(panelStyles).toMatch(
      /\.selectGroup\s*\{[\s\S]*display:\s*grid;[\s\S]*grid-template-columns:\s*minmax\(0,\s*1\.15fr\)\s+minmax\(0,\s*0\.85fr\);/
    );
    expect(panelStyles).toMatch(
      /\.providerSelect,\s*\.modelSelect\s*\{[\s\S]*overflow:\s*hidden;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/
    );
    expect(panelStyles).toMatch(
      /\.actionsRow\s*\{[\s\S]*display:\s*flex;[\s\S]*align-items:\s*center;[\s\S]*gap:\s*12px;/
    );
    expect(panelStyles).toMatch(
      /\.statusText\s*\{[\s\S]*min-width:\s*0;[\s\S]*overflow:\s*hidden;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/
    );
  });

  it('calls provider change callback', () => {
    const onProviderChange = vi.fn();

    render(
      <AiFeatureProviderModelPanel
        config={{
          ...config,
          provider: 'claude',
          effectiveProvider: 'claude',
          resolutionSource: 'manual',
        }}
        settingsKeyPrefix="settings.commit.providerModel"
        providerKeyPrefix="settings.basic.promptEnhancer.provider"
        onProviderChange={onProviderChange}
        onModelChange={vi.fn()}
      />
    );

    const [providerSelect] = screen.getAllByRole('combobox');
    fireEvent.change(providerSelect, { target: { value: 'codex' } });

    expect(onProviderChange).toHaveBeenCalledWith('codex');
  });

  it('calls model change callback from model selector', () => {
    const onModelChange = vi.fn();

    render(
      <AiFeatureProviderModelPanel
        config={config}
        settingsKeyPrefix="settings.commit.providerModel"
        providerKeyPrefix="settings.basic.promptEnhancer.provider"
        onProviderChange={vi.fn()}
        onModelChange={onModelChange}
      />
    );

    const [, modelSelect] = screen.getAllByRole('combobox');
    fireEvent.change(modelSelect, { target: { value: 'gpt-5.4' } });

    expect(onModelChange).toHaveBeenCalledWith('gpt-5.4');
  });

  it('recomputes model options when model registry updates', async () => {
    const { subscribeModelRegistry, getModelsForProvider } = await import('../../../../../src/utils/modelRegistry');
    const mockedSubscribe = vi.mocked(subscribeModelRegistry);
    const mockedGetModels = vi.mocked(getModelsForProvider);
    let registryListener: (() => void) | undefined;
    mockedSubscribe.mockImplementation((listener) => {
      registryListener = listener;
      return () => {};
    });

    render(
      <AiFeatureProviderModelPanel
        config={config}
        settingsKeyPrefix="settings.commit.providerModel"
        providerKeyPrefix="settings.basic.promptEnhancer.provider"
        onProviderChange={vi.fn()}
        onModelChange={vi.fn()}
      />
    );

    const initialCallCount = mockedGetModels.mock.calls.length;

    // 模拟 registry 推送更新:listener 触发 version 递增 → useMemo 重算 → getModelsForProvider 再次调用
    act(() => { registryListener?.(); });

    expect(mockedGetModels.mock.calls.length).toBeGreaterThan(initialCallCount);
  });
});
