import { subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM } from '../generated/protocol';
import { applyAppearanceConfig } from './appearance';
import { applyAvatarConfig } from './avatar';
import {
  applyCodeFontConfig,
  applyEditorTypographyConfig,
  applyUiFontConfig,
} from './fonts';
import { applyLanguageConfig } from './language';
import type { WebviewBootstrapPayload } from '../types/webviewBootstrap';

function parseBootstrapPayload(raw: WebviewBootstrapPayload | string): WebviewBootstrapPayload | null {
  if (typeof raw !== 'string') {
    return raw;
  }

  try {
    return JSON.parse(raw) as WebviewBootstrapPayload;
  } catch (error) {
    console.error('[Bootstrap] Failed to parse webview bootstrap payload:', error, raw);
    return null;
  }
}

export function initWebviewBootstrap(): void {
  subscribeEvent<WebviewBootstrapPayload | string>(DOWNSTREAM.WEBVIEW_BOOTSTRAP, (raw) => {
    const payload = parseBootstrapPayload(raw);
    if (!payload) {
      return;
    }

    if (payload.editorFontConfig) {
      applyEditorTypographyConfig(payload.editorFontConfig);
    }
    if (payload.uiFontConfig) {
      applyUiFontConfig(payload.uiFontConfig);
    }
    if (payload.codeFontConfig) {
      applyCodeFontConfig(payload.codeFontConfig);
    }
    if (payload.languageConfig) {
      applyLanguageConfig(payload.languageConfig);
    }
    if (payload.appearanceConfig) {
      applyAppearanceConfig(payload.appearanceConfig);
    }
    if (payload.avatarConfig) {
      applyAvatarConfig(payload.avatarConfig);
    }
  });
}
