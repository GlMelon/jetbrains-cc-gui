import type { WebviewBootstrapPayloadWire } from '../generated/protocol';
import type { AvatarConfig } from './avatar';
import type { CodeFontConfig, UiFontConfig } from './uiFontConfig';

export interface EditorFontConfig {
  fontFamily: string;
  fontSize: number;
  lineSpacing: number;
  fallbackFonts?: string[];
}

export interface LanguageConfig {
  language: string;
  source?: string;
  ideaLocale?: string;
}

export interface AppearanceConfig {
  themePreference?: string;
  fontSizeLevel?: number;
  diffTheme?: string;
  chatBgColor?: { light?: string; dark?: string };
  userMsgColor?: { light?: string; dark?: string };
}

export interface WebviewBootstrapPayload extends WebviewBootstrapPayloadWire {
  editorFontConfig: EditorFontConfig | null;
  uiFontConfig: UiFontConfig | null;
  codeFontConfig: CodeFontConfig | null;
  languageConfig: LanguageConfig | null;
  appearanceConfig: AppearanceConfig | null;
  avatarConfig: AvatarConfig | null;
}
