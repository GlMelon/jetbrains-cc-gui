import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ProviderDialog from '../../src/components/ProviderDialog';
import type { ProviderConfig } from '../../src/types/provider';

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

// 引导流已改为 3 步:0=接入方式 1=凭证 2=模型映射。
// 模型映射 / 保存 相关断言需先从 step 0 导航到 step 2。
// 编辑模式 providerName 已有值可直接前进;add 模式需填充 providerName 才能通过 step 1 门禁。
const NEXT_BTN_NAME = 'common.next';
const navigateToModelsStep = (fillProviderName?: string) => {
  // step 0 → 1:点 Next(接入方式步的 Next 总可点)
  fireEvent.click(screen.getByRole('button', { name: NEXT_BTN_NAME }));
  // step 1:凭证步。若需要,填充 providerName 以满足前进门禁。
  const nameInput = screen.getByPlaceholderText('settings.provider.dialog.providerNamePlaceholder') as HTMLInputElement;
  if (fillProviderName !== undefined) {
    fireEvent.change(nameInput, { target: { value: fillProviderName } });
  }
  // step 1 → 2:点 Next 进入模型映射步
  fireEvent.click(screen.getByRole('button', { name: NEXT_BTN_NAME }));
};

describe('ProviderDialog', () => {
  it('add mode shows official preset selected by default, model mapping reachable', () => {
    render(
      <ProviderDialog
        isOpen
        provider={null}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );

    // Step 0:Official preset should be present
    expect(screen.getByRole('radio', { name: 'settings.provider.dialog.officialPreset' })).toBeTruthy();
    // 导航到 step 2 后模型映射可见
    navigateToModelsStep('Test');
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

    // Step 0:Click a third-party preset (zhipu)
    const zhipuBtn = screen.getByRole('radio', { name: 'settings.provider.presets.zhipu' });
    fireEvent.click(zhipuBtn);

    // 导航到 step 2:Model mapping should remain visible
    navigateToModelsStep('Test');
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

    // 编辑模式 providerName 已有值,可直接导航
    navigateToModelsStep();

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

    navigateToModelsStep();

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

    navigateToModelsStep();

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

    navigateToModelsStep();

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

    navigateToModelsStep();

    fireEvent.click(screen.getByRole('button', { name: 'settings.provider.dialog.saveChanges' }));

    const payload = onSave.mock.calls[0]?.[0] as { jsonConfig: string };
    const env = JSON.parse(payload.jsonConfig).env ?? {};

    expect(env.ANTHROPIC_DEFAULT_HAIKU_MODEL).toBeUndefined();
    expect(env.ANTHROPIC_SMALL_FAST_MODEL).toBe('legacy-haiku-model');
  });

  it('renders a single card layer without a nested .dialog wrapper inside .dialog-base', () => {
    // 回归守护:历史遗留的内层 <div className="dialog provider-dialog"> 与
    // BaseDialog 的 .dialog-base 叠成双层卡片(双重背景/边框/阴影/圆角)。
    // 修复后 .dialog-base 的直接子元素不应再携带 .dialog token。
    render(
      <ProviderDialog
        isOpen
        provider={null}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );
    // BaseDialog 经 portal 渲染到 document.body，须从 body 查询 .dialog-base
    const base = document.body.querySelector('.dialog-base');
    expect(base).toBeTruthy();

    const nestedDialogCard = Array.from(base!.children).find((el) =>
      el.classList.contains('dialog'),
    );
    expect(nestedDialogCard).toBeUndefined();
  });
});
