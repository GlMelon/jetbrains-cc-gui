import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import OpenCodeProviderSection from './index';
import { SPECIAL_PROVIDER_IDS } from '../../../types/provider';

const providerListStyles = readFileSync(
  'src/components/settings/ProviderList/style.module.less',
  'utf8'
);

const zhLocale = JSON.parse(readFileSync('src/i18n/locales/zh.json', 'utf8'));
const enLocale = JSON.parse(readFileSync('src/i18n/locales/en.json', 'utf8'));

const translations: Record<string, string> = {
  'settings.openCodeProvider.title': 'OpenCode 供应商',
  'settings.openCodeProvider.description': '管理 OpenCode 供应商',
  'settings.openCodeProvider.emptyProvider': 'No OpenCode providers configured',
  'settings.openCodeProvider.dialog.localConfigProviderName': '使用本地 opencode.json',
  'settings.openCodeProvider.dialog.localConfigProviderDescription': '显式授权读取：~/.config/opencode/opencode.json',
  'settings.openCodeProvider.dialog.localConfigAuthorizeTitle': '授权读取本地 OpenCode 配置',
  'settings.openCodeProvider.dialog.localConfigAuthorizeMessage': '插件将读取 ~/.config/opencode/opencode.json 中已有的本地配置。',
  'settings.openCodeProvider.dialog.localConfigAuthorizeDetail': '此操作不会修改或覆盖您的 opencode.json，您可以随时取消授权。',
  'settings.openCodeProvider.dialog.localConfigDisableTitle': '取消本地 OpenCode 配置授权',
  'settings.openCodeProvider.dialog.localConfigDisableMessage': '插件将停止读取 ~/.config/opencode/opencode.json。',
  'settings.provider.loading': 'Loading',
  'settings.provider.allProviders': 'All Providers',
  'settings.provider.authorizeAndEnable': '授权并启用',
  'settings.provider.revokeAuthorization': '取消授权',
  'settings.provider.enable': 'Enable',
  'settings.provider.inUse': 'In Use',
  'settings.provider.dragToSort': 'Drag to sort',
  'common.add': 'Add',
  'common.cancel': '取消',
  'common.edit': 'Edit',
  'common.delete': 'Delete',
};

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => {
      const template = translations[key];
      if (!template) {
        return key;
      }
      if (!options) {
        return template;
      }
      return Object.entries(options).reduce(
        (result, [token, value]) => result.replace(`{{${token}}}`, value),
        template
      );
    },
  }),
}));

describe('OpenCodeProviderSection', () => {
  const onAddOpenCodeProvider = vi.fn();
  const onEditOpenCodeProvider = vi.fn();
  const onDeleteOpenCodeProvider = vi.fn();
  const onSwitchOpenCodeProvider = vi.fn();
  const onRevokeOpenCodeLocalConfigAuthorization = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('defines localized local config dialog copy in zh and en locale files', () => {
    const requiredKeys = [
      'localConfigProviderName',
      'localConfigProviderDescription',
      'localConfigAuthorizeTitle',
      'localConfigAuthorizeMessage',
      'localConfigAuthorizeDetail',
      'localConfigDisableTitle',
      'localConfigDisableMessage',
    ];

    for (const locale of [zhLocale, enLocale]) {
      const dialog = locale.settings.openCodeProvider.dialog;
      for (const key of requiredKeys) {
        expect(dialog[key]).toEqual(expect.any(String));
        expect(dialog[key]).not.toHaveLength(0);
      }
    }
  });

  it('renders translated local config authorization copy and confirms before enabling', () => {
    render(
      <OpenCodeProviderSection
        openCodeProviders={[
          {
            id: SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG,
            name: 'Virtual local opencode config',
            isActive: false,
            isOpenCodeLocalConfigProvider: true,
          },
        ]}
        openCodeLoading={false}
        onAddOpenCodeProvider={onAddOpenCodeProvider}
        onEditOpenCodeProvider={onEditOpenCodeProvider}
        onDeleteOpenCodeProvider={onDeleteOpenCodeProvider}
        onSwitchOpenCodeProvider={onSwitchOpenCodeProvider}
        onRevokeOpenCodeLocalConfigAuthorization={onRevokeOpenCodeLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('使用本地 opencode.json')).toBeTruthy();
    expect(screen.getByText('显式授权读取：~/.config/opencode/opencode.json')).toBeTruthy();

    fireEvent.click(screen.getAllByRole('button', { name: '授权并启用' })[0]);

    const title = screen.getByText('授权读取本地 OpenCode 配置');
    expect(title).toBeTruthy();

    const dialog = title.closest('div')?.parentElement;
    const content = dialog?.querySelector('[class*="warningContent"]');
    expect(content?.textContent).toContain('插件将读取 ~/.config/opencode/opencode.json 中已有的本地配置。');
    expect(content?.textContent).toContain('此操作不会修改或覆盖您的 opencode.json，您可以随时取消授权。');

    const confirmButton = dialog?.querySelectorAll('button')[1];
    expect(confirmButton).toBeTruthy();
    fireEvent.click(confirmButton as HTMLButtonElement);

    expect(onSwitchOpenCodeProvider).toHaveBeenCalledWith(SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG);
  });

  it('wraps long warning dialog content inside the dialog boundary', () => {
    expect(providerListStyles).toMatch(/\.warningDialog\s*\{[\s\S]*max-width:\s*calc\(100vw\s*-\s*32px\);/);
    expect(providerListStyles).toMatch(/\.warningDialog\s*\{[\s\S]*box-sizing:\s*border-box;/);
    expect(providerListStyles).toMatch(/\.warningContent\s*\{[\s\S]*overflow-wrap:\s*anywhere;/);
    expect(providerListStyles).toMatch(/\.warningActions\s*\{[\s\S]*flex-wrap:\s*wrap;/);
  });
});
