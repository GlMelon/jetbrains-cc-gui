import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ProviderDialog from './ProviderDialog';
import type { ProviderConfig } from '../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.name ?? key,
  }),
}));

const createProvider = (): ProviderConfig => ({
  id: 'provider-zhipu',
  name: 'Zhipu',
  isActive: true,
  settingsConfig: {
    env: {
      ANTHROPIC_BASE_URL: 'https://open.bigmodel.cn/api/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-4.7',
    },
  },
});

const createCustomProxyProvider = (): ProviderConfig => ({
  id: 'provider-custom',
  name: 'My Proxy',
  isActive: true,
  settingsConfig: {
    env: {
      ANTHROPIC_BASE_URL: 'https://my-proxy.example.com/v1',
      ANTHROPIC_AUTH_TOKEN: 'sk-test',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'custom-haiku',
      ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME: 'Custom Haiku',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'custom-sonnet',
      ANTHROPIC_DEFAULT_SONNET_MODEL_NAME: 'Custom Sonnet',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'custom-opus',
      ANTHROPIC_DEFAULT_OPUS_MODEL_NAME: 'Custom Opus',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'custom-fable',
      ANTHROPIC_DEFAULT_FABLE_MODEL_NAME: 'Custom Fable',
    },
  },
});

const createLegacyHaikuProvider = (): ProviderConfig => ({
  id: 'provider-legacy-haiku',
  name: 'Legacy Haiku Provider',
  isActive: true,
  settingsConfig: {
    env: {
      ANTHROPIC_BASE_URL: 'https://legacy.example.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: 'sk-legacy',
      ANTHROPIC_SMALL_FAST_MODEL: 'legacy-haiku-model',
    },
  },
});

describe('ProviderDialog', () => {
  it('add mode shows official preset selected by default with model mapping visible', () => {
    render(
      <ProviderDialog
        isOpen
        provider={null}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );

    // Official preset should be present and selected
    expect(screen.getByRole('radio', { name: 'settings.provider.dialog.officialPreset' })).toBeTruthy();
    // Model mapping should be visible
    expect(screen.getByText('settings.provider.dialog.modelMapping')).toBeTruthy();
  });

  it('third-party preset still shows model mapping section', () => {
    render(
      <ProviderDialog
        isOpen
        provider={null}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );

    // Click a third-party preset (zhipu)
    const zhipuBtn = screen.getByRole('radio', { name: 'settings.provider.presets.zhipu' });
    fireEvent.click(zhipuBtn);

    // Model mapping should remain visible
    expect(screen.getByText('settings.provider.dialog.modelMapping')).toBeTruthy();
  });

  it('editing provider with unrecognized proxy URL still shows model mapping', () => {
    render(
      <ProviderDialog
        isOpen
        provider={createCustomProxyProvider()}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );

    // Model mapping should be visible even for unrecognized proxy URLs
    expect(screen.getByText('settings.provider.dialog.modelMapping')).toBeTruthy();
    // Should have the custom model values populated
    const requestModels = screen.getAllByLabelText('settings.provider.dialog.requestModel') as HTMLInputElement[];
    expect(requestModels[0].value).toBe('custom-sonnet');
    expect(requestModels[1].value).toBe('custom-opus');
    expect(requestModels[2].value).toBe('custom-fable');
    expect(requestModels[3].value).toBe('custom-haiku');
  });

  it('saves display names separately from actual request models', () => {
    const onSave = vi.fn();

    render(
      <ProviderDialog
        isOpen
        provider={createCustomProxyProvider()}
        onClose={vi.fn()}
        onSave={onSave}
        addToast={vi.fn()}
      />,
    );

    fireEvent.change(screen.getAllByLabelText('settings.provider.dialog.displayName')[0], {
      target: { value: 'mimo-v2.5' },
    });
    fireEvent.change(screen.getAllByLabelText('settings.provider.dialog.requestModel')[0], {
      target: { value: 'mimo-v2.5-pro' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'settings.provider.dialog.saveChanges' }));

    const payload = onSave.mock.calls[0]?.[0] as { jsonConfig: string };
    const env = JSON.parse(payload.jsonConfig).env ?? {};

    expect(env.ANTHROPIC_DEFAULT_SONNET_MODEL_NAME).toBe('mimo-v2.5');
    expect(env.ANTHROPIC_DEFAULT_SONNET_MODEL).toBe('mimo-v2.5-pro');
    expect(env.ANTHROPIC_DEFAULT_OPUS_MODEL_NAME).toBe('Custom Opus');
    expect(env.ANTHROPIC_DEFAULT_FABLE_MODEL_NAME).toBe('Custom Fable');
    expect(env.ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME).toBe('Custom Haiku');
  });

  it('does not backfill the Haiku field from ANTHROPIC_SMALL_FAST_MODEL', () => {
    render(
      <ProviderDialog
        isOpen
        provider={createLegacyHaikuProvider()}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );

    expect((screen.getAllByLabelText('settings.provider.dialog.requestModel')[3] as HTMLInputElement).value).toBe('');
  });

  it('clearing model mapping fields should remove residual ANTHROPIC_MODEL on save', () => {
    const onSave = vi.fn();

    render(
      <ProviderDialog
        isOpen
        provider={createProvider()}
        onClose={vi.fn()}
        onSave={onSave}
        addToast={vi.fn()}
      />,
    );

    const requestModels = screen.getAllByLabelText('settings.provider.dialog.requestModel');
    fireEvent.change(requestModels[0], {
      target: { value: '' },
    });
    fireEvent.change(requestModels[1], {
      target: { value: '' },
    });
    fireEvent.change(requestModels[2], {
      target: { value: '' },
    });
    fireEvent.change(requestModels[3], {
      target: { value: '' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'settings.provider.dialog.saveChanges' }));

    expect(onSave).toHaveBeenCalledTimes(1);

    const payload = onSave.mock.calls[0]?.[0] as { jsonConfig: string };
    const parsed = JSON.parse(payload.jsonConfig);
    const env = parsed.env ?? {};

    expect(env.ANTHROPIC_BASE_URL).toBe('https://open.bigmodel.cn/api/anthropic');
    expect(env.ANTHROPIC_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_DEFAULT_SONNET_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_DEFAULT_OPUS_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_DEFAULT_FABLE_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_DEFAULT_HAIKU_MODEL).toBeUndefined();
  });

  it('preserves ANTHROPIC_SMALL_FAST_MODEL without turning it into a Haiku override', () => {
    const onSave = vi.fn();

    render(
      <ProviderDialog
        isOpen
        provider={createLegacyHaikuProvider()}
        onClose={vi.fn()}
        onSave={onSave}
        addToast={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'settings.provider.dialog.saveChanges' }));

    const payload = onSave.mock.calls[0]?.[0] as { jsonConfig: string };
    const env = JSON.parse(payload.jsonConfig).env ?? {};

    expect(env.ANTHROPIC_DEFAULT_HAIKU_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_SMALL_FAST_MODEL).toBe('legacy-haiku-model');
  });
});
