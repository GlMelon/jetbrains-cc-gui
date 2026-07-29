/**
 * 外观配置(主题/字号/diff 主题/背景色/消息色)bootstrap 模块。
 *
 * 接收 Java 注入的 config.json 外观段(冷缓存回灌源),**仅在 localStorage 对应键
 * 缺失时**填充,绝不覆盖前端已写入的值(前端是 localStorage 的热路径单写者)。
 * 填充后派发 appearance-config-applied 事件,通知 useThemeInit / useSettingsThemeSync
 * 从 localStorage 同步 React state(主要服务于"清 IDE 缓存后恢复"场景)。
 *
 * 热路径(用户改色)由前端即时写 localStorage + 防抖 set_appearance_config 落盘,
 * 不经此模块;此模块只在冷启动/清缓存后回灌缺失值。
 */
import { subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM } from '../generated/protocol';
import { debugLog } from '../utils/debug';
import { registerLegacyAlias } from '../bridge';
import { writeScopedColor, isValidHexColor, type ColorBaseKey } from '../utils/appearanceColors';

type ThemePreference = 'light' | 'dark' | 'system';
type DiffThemeMode = 'follow' | 'editor' | 'light' | 'soft-dark';
type ThemeKey = 'light' | 'dark';

interface AppearanceConfigPayload {
  themePreference?: string;
  fontSizeLevel?: number;
  diffTheme?: string;
  chatBgColor?: { light?: string; dark?: string };
  userMsgColor?: { light?: string; dark?: string };
}

function parseThemePreference(raw: unknown): ThemePreference | null {
  return raw === 'light' || raw === 'dark' || raw === 'system' ? (raw as ThemePreference) : null;
}

function parseDiffTheme(raw: unknown): DiffThemeMode | null {
  return raw === 'follow' || raw === 'editor' || raw === 'light' || raw === 'soft-dark'
    ? (raw as DiffThemeMode)
    : null;
}

function parseFontSize(raw: unknown): number | null {
  return typeof raw === 'number' && Number.isInteger(raw) && raw >= 1 && raw <= 6 ? raw : null;
}

/**
 * 应用外观配置(支持对象/JSON 字符串两种形式)。
 * 仅填充 localStorage 中缺失的键;有任意键被填充时派发同步事件。
 */
export function applyAppearanceConfig(rawConfig: AppearanceConfigPayload | string): void {
  let config: AppearanceConfigPayload;
  if (typeof rawConfig === 'string') {
    try {
      config = JSON.parse(rawConfig) as AppearanceConfigPayload;
    } catch (error) {
      console.error('[Main] Failed to parse appearance config:', error, rawConfig);
      return;
    }
  } else {
    config = rawConfig;
  }

  let changed = false;

  // 主题偏好(仅当 localStorage 无 theme 键时回灌)
  const tp = parseThemePreference(config.themePreference);
  if (tp && localStorage.getItem('theme') === null) {
    localStorage.setItem('theme', tp);
    changed = true;
  }

  // 字号
  const fs = parseFontSize(config.fontSizeLevel);
  if (fs !== null && localStorage.getItem('fontSizeLevel') === null) {
    localStorage.setItem('fontSizeLevel', fs.toString());
    changed = true;
  }

  // diff 主题
  const dt = parseDiffTheme(config.diffTheme);
  if (dt && localStorage.getItem('diffTheme') === null) {
    localStorage.setItem('diffTheme', dt);
    changed = true;
  }

  // 按主题分别存储的颜色
  (['chatBgColor', 'userMsgColor'] as const).forEach((baseKey: ColorBaseKey) => {
    const scoped = config[baseKey];
    if (!scoped || typeof scoped !== 'object') return;
    (['light', 'dark'] as const).forEach((theme: ThemeKey) => {
      const v = scoped[theme];
      if (localStorage.getItem(`${baseKey}.${theme}`) === null && isValidHexColor(v)) {
        writeScopedColor(baseKey, theme, v);
        changed = true;
      }
    });
  });

  debugLog('[Main] Applied appearance config (cold-cache hydration):', config, 'changed:', changed);

  // 仅在确实回灌了新值时通知 hook 同步(避免与 Java 落盘回推形成循环)
  if (changed) {
    window.dispatchEvent(new CustomEvent('appearance-config-applied'));
  }
}

/**
 * 注册全局外观配置处理器,并消费 Java 早于 JS 注入的 pending 配置。
 *
 * [归一化重构] applyAppearanceConfig 经 compat 别名转发到 bridgeHub,
 * 订阅者直接操作 localStorage(不进 React state)。pending drain 保留。
 */
export function initAppearance(): void {
  // [归一化] applyAppearanceConfig → appearance.apply
  registerLegacyAlias('applyAppearanceConfig', DOWNSTREAM.APPEARANCE_APPLY);
  subscribeEvent(DOWNSTREAM.APPEARANCE_APPLY, (json) => applyAppearanceConfig(json as string));

  // 处理 Java 早于 JS 的竞态(WebviewInitializer 注入时本函数可能尚未注册)
  if (window.__pendingAppearanceConfig) {
    debugLog('[Main] Found pending appearance config, applying...');
    applyAppearanceConfig(window.__pendingAppearanceConfig);
    delete window.__pendingAppearanceConfig;
  }
}
