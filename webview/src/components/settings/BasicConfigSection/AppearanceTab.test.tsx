import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AppearanceTab from './AppearanceTab';
import styles from './style.module.less';
import { AVATAR_MODE, AVATAR_PRESET } from '../../../types/avatar';
import { PROVIDER_TYPE } from '../../../generated/protocol';

const changeLanguageMock = vi.fn();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: {
      language: 'zh',
      changeLanguage: changeLanguageMock,
    },
  }),
}));

describe('AppearanceTab ui font selector', () => {
  const bridgeCall = (type: string, content = '') =>
    JSON.stringify({ type, content });

  afterEach(() => {
    localStorage.clear();
  });

  const renderAppearanceTab = () => render(
    <AppearanceTab
      {...({
        theme: 'dark',
        onThemeChange: vi.fn(),
        fontSizeLevel: 3,
        onFontSizeLevelChange: vi.fn(),
        editorFontConfig: {
          fontFamily: 'Monaco',
          fontSize: 14,
          lineSpacing: 1.35,
        },
        uiFontConfig: {
          mode: 'followEditor',
          effectiveMode: 'followEditor',
          fontFamily: 'Monaco',
          fontSize: 14,
          lineSpacing: 1.35,
        },
        codeFontConfig: {
          mode: 'followEditor',
          effectiveMode: 'followEditor',
          fontFamily: 'Monaco',
          fontSize: 14,
          lineSpacing: 1.35,
        },
        onUiFontSelectionChange: vi.fn(),
        onUiFontCustomPathChange: vi.fn(),
        onSaveUiFontCustomPath: vi.fn(),
        onBrowseUiFontFile: vi.fn(),
        onCodeFontSelectionChange: vi.fn(),
        onSaveCodeFontCustomPath: vi.fn(),
        onBrowseCodeFontFile: vi.fn(),
      } as any)}
    />
  );

  it('delegates follow-IDE selection to Java without touching localStorage', () => {
    const sendToJava = vi.fn();
    window.sendToJava = sendToJava;
    localStorage.setItem('languageSelectionMode', 'manual');

    renderAppearanceTab();

    fireEvent.change(screen.getAllByRole('combobox')[0], {
      target: { value: '__follow_idea__' },
    });

    expect(sendToJava).toHaveBeenCalledWith(bridgeCall('clear_user_language'));
    expect(changeLanguageMock).not.toHaveBeenCalled();
    // Java + main.tsx own the persisted state; component must not write here.
    expect(localStorage.getItem('languageSelectionMode')).toBe('manual');
  });

  it('delegates manual language selection to Java without touching localStorage', () => {
    const sendToJava = vi.fn();
    window.sendToJava = sendToJava;
    changeLanguageMock.mockClear();
    localStorage.setItem('languageSelectionMode', 'followIdea');

    renderAppearanceTab();

    fireEvent.change(screen.getAllByRole('combobox')[0], {
      target: { value: 'en' },
    });

    expect(sendToJava).toHaveBeenCalledWith(
      bridgeCall('set_user_language', JSON.stringify({ language: 'en' }))
    );
    expect(changeLanguageMock).toHaveBeenCalledWith('en');
    expect(localStorage.getItem('languageSelectionMode')).toBe('followIdea');
    expect(localStorage.getItem('language')).toBeNull();
  });

  it('resyncs the selection when main.tsx dispatches language-config-applied', () => {
    window.sendToJava = vi.fn();
    localStorage.setItem('languageSelectionMode', 'manual');

    renderAppearanceTab();
    const select = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(select.value).toBe('zh');

    // Simulate Java pushing the authoritative followIdea state after a failure
    // or external config edit.
    act(() => {
      localStorage.setItem('languageSelectionMode', 'followIdea');
      window.dispatchEvent(new CustomEvent('language-config-applied', {
        detail: { language: 'zh', selectionMode: 'followIdea' },
      }));
    });

    expect(select.value).toBe('__follow_idea__');
  });

  it('renders only follow-editor and custom options, plus custom path controls for custom mode', () => {
    const props = {
      theme: 'dark',
      onThemeChange: vi.fn(),
      fontSizeLevel: 3,
      onFontSizeLevelChange: vi.fn(),
      editorFontConfig: {
        fontFamily: 'Monaco',
        fontSize: 14,
        lineSpacing: 1.35,
      },
      uiFontConfig: {
        mode: 'customFile',
        effectiveMode: 'followEditor',
        customFontPath: '/tmp/MapleMono.ttf',
        fontFamily: 'Monaco',
        fontSize: 14,
        lineSpacing: 1.35,
        warning: 'font unavailable, currently using editor font',
      },
      codeFontConfig: {
        mode: 'followEditor',
        effectiveMode: 'followEditor',
        fontFamily: 'Monaco',
        fontSize: 14,
        lineSpacing: 1.35,
      },
      onUiFontSelectionChange: vi.fn(),
      onUiFontCustomPathChange: vi.fn(),
      onSaveUiFontCustomPath: vi.fn(),
      onBrowseUiFontFile: vi.fn(),
      onCodeFontSelectionChange: vi.fn(),
      onSaveCodeFontCustomPath: vi.fn(),
      onBrowseCodeFontFile: vi.fn(),
    } as any;

    render(<AppearanceTab {...props} />);

    const select = screen.getByRole('combobox', { name: /settings.basic.editorFont.label/i });
    const options = within(select).getAllByRole('option');

    expect(select).toBeTruthy();
    expect(options).toHaveLength(2);
    expect(screen.getByRole('option', { name: /settings.basic.editorFont.followOption/i })).toBeTruthy();
    expect(screen.getByRole('option', { name: /settings.basic.editorFont.customOption/i })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: /settings.basic.codeFont.label/i })).toBeTruthy();
    expect(screen.getByDisplayValue('/tmp/MapleMono.ttf')).toBeTruthy();
    expect(screen.getByText(/font unavailable/i)).toBeTruthy();
  });

  it('notifies selection changes immediately for custom font mode', () => {
    const onUiFontSelectionChange = vi.fn();

    render(
      <AppearanceTab
        {...({
          theme: 'dark',
          onThemeChange: vi.fn(),
          fontSizeLevel: 3,
          onFontSizeLevelChange: vi.fn(),
          editorFontConfig: {
            fontFamily: 'Monaco',
            fontSize: 14,
            lineSpacing: 1.35,
          },
          uiFontConfig: {
            mode: 'followEditor',
            effectiveMode: 'followEditor',
            customFontPath: '/tmp/MapleMono.ttf',
            fontFamily: 'Monaco',
            fontSize: 14,
            lineSpacing: 1.35,
          },
          codeFontConfig: {
            mode: 'followEditor',
            effectiveMode: 'followEditor',
            fontFamily: 'Monaco',
            fontSize: 14,
            lineSpacing: 1.35,
          },
          onUiFontSelectionChange,
          onUiFontCustomPathChange: vi.fn(),
          onSaveUiFontCustomPath: vi.fn(),
          onBrowseUiFontFile: vi.fn(),
          onCodeFontSelectionChange: vi.fn(),
          onSaveCodeFontCustomPath: vi.fn(),
          onBrowseCodeFontFile: vi.fn(),
        } as any)}
      />
    );

    fireEvent.change(screen.getByRole('combobox', { name: /settings.basic.editorFont.label/i }), {
      target: { value: 'customFile' },
    });

    expect(onUiFontSelectionChange).toHaveBeenCalledTimes(1);
    expect(onUiFontSelectionChange).toHaveBeenCalledWith('customFile');
  });

  it('picks the per-theme default background color from the resolvedTheme prop', () => {
    window.sendToJava = vi.fn();

    // resolvedTheme=light → 颜色选择器默认值应是亮色默认(#ffffff)
    const { container: lightContainer } = render(
      <AppearanceTab
        {...({
          theme: 'light',
          resolvedTheme: 'light',
          onThemeChange: vi.fn(),
          fontSizeLevel: 3,
          onFontSizeLevelChange: vi.fn(),
          editorFontConfig: { fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          uiFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          codeFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          onUiFontSelectionChange: vi.fn(),
          onUiFontCustomPathChange: vi.fn(),
          onSaveUiFontCustomPath: vi.fn(),
          onBrowseUiFontFile: vi.fn(),
          onCodeFontSelectionChange: vi.fn(),
          onSaveCodeFontCustomPath: vi.fn(),
          onBrowseCodeFontFile: vi.fn(),
        } as any)}
      />
    );
    const lightPicker = lightContainer.querySelector('input[type="color"]') as HTMLInputElement;
    expect(lightPicker.value).toBe('#ffffff');

    // 对照:resolvedTheme=dark → 暗色默认(#1e1e1e),证明按主题隔离
    const { container: darkContainer } = render(
      <AppearanceTab
        {...({
          theme: 'dark',
          resolvedTheme: 'dark',
          onThemeChange: vi.fn(),
          fontSizeLevel: 3,
          onFontSizeLevelChange: vi.fn(),
          editorFontConfig: { fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          uiFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          codeFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          onUiFontSelectionChange: vi.fn(),
          onUiFontCustomPathChange: vi.fn(),
          onSaveUiFontCustomPath: vi.fn(),
          onBrowseUiFontFile: vi.fn(),
          onCodeFontSelectionChange: vi.fn(),
          onSaveCodeFontCustomPath: vi.fn(),
          onBrowseCodeFontFile: vi.fn(),
        } as any)}
      />
    );
    const darkPicker = darkContainer.querySelector('input[type="color"]') as HTMLInputElement;
    expect(darkPicker.value).toBe('#1e1e1e');
  });

  it('keeps avatar choices inside a drawer and opens AI tab with preview', () => {
    render(
      <AppearanceTab
        {...({
          theme: 'dark',
          onThemeChange: vi.fn(),
          fontSizeLevel: 3,
          onFontSizeLevelChange: vi.fn(),
          editorFontConfig: { fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          uiFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          codeFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          currentProvider: PROVIDER_TYPE.CODEX,
          avatarConfig: {
            assistant: { mode: AVATAR_MODE.PRESET, preset: AVATAR_PRESET.ASSISTANT_DEFAULT },
            user: { mode: AVATAR_MODE.PRESET, preset: AVATAR_PRESET.USER_DEFAULT },
            assistantPresetOptions: [
              { value: PROVIDER_TYPE.CODEX, label: 'Codex' },
            ],
          },
          onUiFontSelectionChange: vi.fn(),
          onUiFontCustomPathChange: vi.fn(),
          onSaveUiFontCustomPath: vi.fn(),
          onBrowseUiFontFile: vi.fn(),
          onCodeFontSelectionChange: vi.fn(),
          onSaveCodeFontCustomPath: vi.fn(),
          onBrowseCodeFontFile: vi.fn(),
          onAssistantAvatarChange: vi.fn(),
          onUserAvatarChange: vi.fn(),
          onUploadAssistantAvatar: vi.fn(),
          onUploadUserAvatar: vi.fn(),
        } as any)}
      />
    );

    expect(screen.queryByRole('tab', { name: /settings\.basic\.avatar\.aiTab/i })).toBeNull();
    expect(screen.queryByText('settings.basic.avatar.followProvider')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /settings\.basic\.avatar\.configure/i }));

    const aiTab = screen.getByRole('tab', { name: /settings\.basic\.avatar\.aiTab/i });
    const userTab = screen.getByRole('tab', { name: /settings\.basic\.avatar\.userTab/i });
    expect(aiTab).toBeTruthy();
    expect(userTab).toBeTruthy();
    expect(aiTab.getAttribute('aria-controls')).toBe('settings-avatar-assistant-panel');
    expect(screen.getByRole('tabpanel').getAttribute('aria-labelledby')).toBe('settings-avatar-assistant-tab');
    expect(screen.getByText('settings.basic.avatar.followProvider')).toBeTruthy();
    expect(screen.getByText('Codex')).toBeTruthy();
    expect(screen.getByText('settings.basic.avatar.assistantPreviewMessage')).toBeTruthy();
    expect(screen.queryByText('settings.basic.avatar.userPreviewMessage')).toBeNull();
  });

  it('shows only user preview on the user tab', () => {
    render(
      <AppearanceTab
        theme="dark"
        onThemeChange={vi.fn()}
        fontSizeLevel={3}
        onFontSizeLevelChange={vi.fn()}
        avatarConfig={{
          assistant: { mode: AVATAR_MODE.PRESET, preset: AVATAR_PRESET.ASSISTANT_DEFAULT },
          user: { mode: AVATAR_MODE.PRESET, preset: AVATAR_PRESET.USER_DEFAULT },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /settings\.basic\.avatar\.configure/i }));
    fireEvent.click(screen.getByRole('tab', { name: /settings\.basic\.avatar\.userTab/i }));
    expect(screen.getByText('settings.basic.avatar.userPreviewMessage')).toBeTruthy();
    expect(screen.queryByText('settings.basic.avatar.assistantPreviewMessage')).toBeNull();
  });

  it('shows uploaded avatar images as drawer frame content', () => {
    const customDataUrl = 'data:image/png;base64,abc';
    const { container } = render(
      <AppearanceTab
        {...({
          theme: 'dark',
          onThemeChange: vi.fn(),
          fontSizeLevel: 3,
          onFontSizeLevelChange: vi.fn(),
          editorFontConfig: { fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          uiFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          codeFontConfig: { mode: 'followEditor', effectiveMode: 'followEditor', fontFamily: 'Monaco', fontSize: 14, lineSpacing: 1.35 },
          avatarConfig: {
            assistant: { mode: AVATAR_MODE.CUSTOM, custom: { id: 'ai', mimeType: 'image/png', dataUrl: customDataUrl } },
            user: { mode: AVATAR_MODE.CUSTOM, custom: { id: 'user', mimeType: 'image/png', dataUrl: customDataUrl } },
            assistantPresetOptions: [
              { value: PROVIDER_TYPE.CODEX, label: 'Codex' },
            ],
          },
          onUiFontSelectionChange: vi.fn(),
          onUiFontCustomPathChange: vi.fn(),
          onSaveUiFontCustomPath: vi.fn(),
          onBrowseUiFontFile: vi.fn(),
          onCodeFontSelectionChange: vi.fn(),
          onSaveCodeFontCustomPath: vi.fn(),
          onBrowseCodeFontFile: vi.fn(),
          onAssistantAvatarChange: vi.fn(),
          onUserAvatarChange: vi.fn(),
          onUploadAssistantAvatar: vi.fn(),
          onUploadUserAvatar: vi.fn(),
        } as any)}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /settings\.basic\.avatar\.configure/i }));

    const images = container.querySelectorAll<HTMLImageElement>(`img.${styles.drawerAvatarImage}`);
    expect(images.length).toBeGreaterThanOrEqual(3);
    expect(Array.from(images).every((image) => image.src === customDataUrl)).toBe(true);
    expect(Array.from(images).every((image) => image.parentElement?.classList.contains(styles.customAvatarFrame))).toBe(true);
  });
});
