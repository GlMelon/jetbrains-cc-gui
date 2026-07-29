/**
 * Font configuration bootstrap module.
 *
 * Manages IDEA editor font and plugin UI font configuration, including:
 * - CSS variable updates for font family, size, and line spacing
 * - Custom @font-face injection for user-provided font files
 * - Synchronizing effective UI font family (editor vs. custom)
 */
import { subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM } from '../generated/protocol';
import { debugLog } from '../utils/debug';
import type { UiFontConfig, CodeFontConfig } from '../types/uiFontConfig';
import { registerLegacyAlias } from '../bridge';

// ---------------------------------------------------------------------------
// State (module-scoped)
// ---------------------------------------------------------------------------

let latestEditorFontConfig: {
  fontFamily: string;
  fontSize: number;
  lineSpacing: number;
  fallbackFonts?: string[];
} | null = null;

let latestUiFontConfig: UiFontConfig | null = null;

const UI_FONT_STYLE_ELEMENT_ID = 'cc-gui-font-face-style';

let currentFontBlobUrl: string | null = null;

// 代码字体(独立于 UI 字体;驱动 message.less/tools.less 的 --cc-gui-code-font-family)
const CODE_FONT_STYLE_ELEMENT_ID = 'cc-gui-code-font-face-style';
let currentCodeFontBlobUrl: string | null = null;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function escapeCssFontName(name: string): string {
  return name.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function buildFontFamilyValue(config: { fontFamily: string; fallbackFonts?: string[] }) {
  const fontParts: string[] = [`'${escapeCssFontName(config.fontFamily)}'`];

  if (config.fallbackFonts && config.fallbackFonts.length > 0) {
    for (const fallback of config.fallbackFonts) {
      fontParts.push(`'${escapeCssFontName(fallback)}'`);
    }
  }

  fontParts.push("'Consolas'", 'monospace');
  return fontParts.join(', ');
}

function escapeCssUrl(url: string): string {
  return url.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n|\r/g, '');
}

function createFontBlobUrl(base64: string, format: string): string {
  const mimeType = format === 'opentype' ? 'font/opentype' : 'font/truetype';
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  const blob = new Blob([bytes], { type: mimeType });
  return URL.createObjectURL(blob);
}

// ---------------------------------------------------------------------------
// Internal application functions
// ---------------------------------------------------------------------------

function setUiFontFaceStyle(config: UiFontConfig) {
  let styleElement = document.getElementById(UI_FONT_STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!styleElement) {
    styleElement = document.createElement('style');
    styleElement.id = UI_FONT_STYLE_ELEMENT_ID;
    document.head.appendChild(styleElement);
  }

  // Revoke previous blob URL to free memory
  if (currentFontBlobUrl) {
    URL.revokeObjectURL(currentFontBlobUrl);
    currentFontBlobUrl = null;
  }

  if (!config.fontUrl && (!config.fontBase64 || !config.fontFormat)) {
    styleElement.textContent = '';
    return;
  }

  const fontFormat = config.fontFormat || 'truetype';
  let fontSourceUrl = config.fontUrl;
  if (!fontSourceUrl && config.fontBase64) {
    fontSourceUrl = createFontBlobUrl(config.fontBase64, fontFormat);
    currentFontBlobUrl = fontSourceUrl;
  }

  // [归一化修正] UI 自定义字体 family 名须与后端 UI_FONT_CUSTOM_FAMILY("CC GUI UI Custom")一致,
  // 否则 @font-face 定义名与引用名不匹配,自定义 UI 字体无法生效。
  const familyName = escapeCssFontName('CC GUI UI Custom');
  styleElement.textContent =
    `@font-face { font-family: '${familyName}'; font-style: normal; font-weight: 100 900;` +
    ` font-display: swap; src: url("${escapeCssUrl(fontSourceUrl || '')}") format('${fontFormat}'); }`;
}

function setCodeFontFaceStyle(config: CodeFontConfig) {
  let styleElement = document.getElementById(CODE_FONT_STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!styleElement) {
    styleElement = document.createElement('style');
    styleElement.id = CODE_FONT_STYLE_ELEMENT_ID;
    document.head.appendChild(styleElement);
  }

  // Revoke previous blob URL to free memory
  if (currentCodeFontBlobUrl) {
    URL.revokeObjectURL(currentCodeFontBlobUrl);
    currentCodeFontBlobUrl = null;
  }

  // followEditor 模式无自定义字体文件 -> 清空 @font-face;customFile 模式后端发送 fontUrl(IDE 资源)
  if (!config.fontUrl && (!config.fontBase64 || !config.fontFormat)) {
    styleElement.textContent = '';
    return;
  }

  const codeFontFormat = config.fontFormat || 'truetype';
  let codeFontSourceUrl = config.fontUrl;
  if (!codeFontSourceUrl && config.fontBase64) {
    codeFontSourceUrl = createFontBlobUrl(config.fontBase64, codeFontFormat);
    currentCodeFontBlobUrl = codeFontSourceUrl;
  }

  // [归一化修正] family 名须与后端 CODE_FONT_CUSTOM_FAMILY("CC GUI Code Custom")一致
  const codeFamilyName = escapeCssFontName('CC GUI Code Custom');
  styleElement.textContent =
    `@font-face { font-family: '${codeFamilyName}'; font-style: normal; font-weight: 100 900;` +
    ` font-display: swap; src: url("${escapeCssUrl(codeFontSourceUrl || '')}") format('${codeFontFormat}'); }`;
}

function syncEffectiveUiFontFamily() {
  const root = document.documentElement;
  const shouldFollowEditor =
    !latestUiFontConfig || latestUiFontConfig.effectiveMode === 'followEditor';

  const sourceConfig = shouldFollowEditor
    ? latestEditorFontConfig || latestUiFontConfig
    : latestUiFontConfig;

  if (!sourceConfig) {
    return;
  }

  const fontFamilyValue = buildFontFamilyValue({
    fontFamily: sourceConfig.fontFamily,
    fallbackFonts: sourceConfig.fallbackFonts ?? latestEditorFontConfig?.fallbackFonts,
  });

  root.style.setProperty('--cc-gui-ui-font-family', fontFamilyValue);
  // Keep legacy variable in sync so existing components continue to pick up the effective UI font.
  root.style.setProperty('--idea-editor-font-family', fontFamilyValue);
}

export function applyEditorTypographyConfig(config: {
  fontFamily: string;
  fontSize: number;
  lineSpacing: number;
  fallbackFonts?: string[];
}) {
  const root = document.documentElement;
  latestEditorFontConfig = config;
  root.style.setProperty('--cc-gui-editor-font-family', buildFontFamilyValue(config));
  root.style.setProperty('--idea-editor-font-size', `${config.fontSize}px`);
  root.style.setProperty('--idea-editor-line-spacing', String(config.lineSpacing));
  syncEffectiveUiFontFamily();
}

export function applyUiFontConfig(config: UiFontConfig | string) {
  const normalizedConfig: UiFontConfig =
    typeof config === 'string' ? JSON.parse(config) as UiFontConfig : config;

  latestUiFontConfig = normalizedConfig;
  setUiFontFaceStyle(normalizedConfig);
  syncEffectiveUiFontFamily();
}

/**
 * [归一化] 代码字体 DOM 应用。
 * 设置 --cc-gui-code-font-family(驱动代码块/工具调用块字体渲染)。
 * - customFile 模式: 后端 resolve fontFamily="CC GUI Code Custom" + fontUrl(IDE 资源),@font-face 注入自定义字体
 * - followEditor 模式: fontFamily=editor 字体名,无 @font-face
 * 与 applyUiFontConfig 完全对称,修复 v0.4.6 迁移遗漏的 font.apply_code 订阅。
 */
export function applyCodeFontConfig(config: CodeFontConfig | string) {
  const normalizedConfig: CodeFontConfig =
    typeof config === 'string' ? JSON.parse(config) as CodeFontConfig : config;

  setCodeFontFaceStyle(normalizedConfig);

  const fontFamilyValue = buildFontFamilyValue({
    fontFamily: normalizedConfig.fontFamily,
    fallbackFonts: normalizedConfig.fallbackFonts ?? latestEditorFontConfig?.fallbackFonts,
  });
  document.documentElement.style.setProperty('--cc-gui-code-font-family', fontFamilyValue);
}

// ---------------------------------------------------------------------------
// Public init function
// ---------------------------------------------------------------------------

/**
 * Register global font config handlers and apply any pending configs that
 * were delivered by the Java side before JS finished loading.
 *
 * [归一化重构] applyIdeaFontConfig/applyUiFontConfig 经 compat 别名转发到 bridgeHub,
 * 订阅者直接调用 DOM 操作函数(不进 React state)。pending drain 保留(Java 可在 JS 加载前推送)。
 */
export function initFonts() {
  // [归一化] applyIdeaFontConfig → font.apply_editor / applyUiFontConfig → font.apply_ui
  registerLegacyAlias('applyIdeaFontConfig', DOWNSTREAM.FONT_APPLY_EDITOR);
  subscribeEvent(DOWNSTREAM.FONT_APPLY_EDITOR, (raw) => {
    // 后端发送 JSON 对象(非字符串),hub 传递原始 payloadJson 字符串,需解析。
    const config = typeof raw === 'string' ? JSON.parse(raw) : raw;
    applyEditorTypographyConfig(config);
  });
  registerLegacyAlias('applyUiFontConfig', DOWNSTREAM.FONT_APPLY_UI);
  subscribeEvent(DOWNSTREAM.FONT_APPLY_UI, (raw) => applyUiFontConfig(raw as string));

  // [归一化] applyCodeFontConfig → font.apply_code(对称 UI 字体;修复 v0.4.6 迁移遗漏)
  registerLegacyAlias('applyCodeFontConfig', DOWNSTREAM.FONT_APPLY_CODE);
  subscribeEvent(DOWNSTREAM.FONT_APPLY_CODE, (raw) => applyCodeFontConfig(raw as string));

  // Check for pending font config (Java side may execute before JS)
  if (window.__pendingFontConfig) {
    debugLog('[Main] Found pending font config, applying...');
    applyEditorTypographyConfig(window.__pendingFontConfig);
    delete window.__pendingFontConfig;
  }

  if (window.__pendingUiFontConfig) {
    debugLog('[Main] Found pending UI font config, applying...');
    applyUiFontConfig(window.__pendingUiFontConfig);
    delete window.__pendingUiFontConfig;
  }

  if (window.__pendingCodeFontConfig) {
    debugLog('[Main] Found pending code font config, applying...');
    applyCodeFontConfig(window.__pendingCodeFontConfig);
    delete window.__pendingCodeFontConfig;
  }
}
