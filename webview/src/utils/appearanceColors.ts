/**
 * 外观颜色按主题工具模块(单一事实源)。
 *
 * 背景色 / 用户消息气泡色按主题(亮/暗)**分别存储**:
 * 切换主题时套用对应主题保存的颜色(未设则回退主题默认色)。
 * localStorage 键命名:`{baseKey}.{theme}`(如 `chatBgColor.dark`)。
 *
 * 供 useThemeInit(主聊天运行时)与 useSettingsThemeSync(设置面板)复用,
 * 确保两处的 CSS 变量写入/移除逻辑保持一致。
 */

/** 解析后的具体主题(亮/暗)。不含 system/null,调用方负责解析。 */
export type Theme = 'light' | 'dark';

/** 受主题作用域控制的颜色项 */
export type ColorBaseKey = 'chatBgColor' | 'userMsgColor';

const HEX_COLOR_REGEX = /^#[0-9a-fA-F]{6}$/;

/** 各 baseKey 对应的 CSS 变量(未设时全部移除以回退 LESS 默认) */
const CSS_VARS: Record<ColorBaseKey, string[]> = {
  chatBgColor: ['--bg-chat'],
  userMsgColor: ['--color-message-user-bg', '--color-message-user-fade'],
};

/** 校验是否为合法的 6 位 hex 颜色(如 `#2b2d30`) */
export function isValidHexColor(c: string | null | undefined): c is string {
  return typeof c === 'string' && HEX_COLOR_REGEX.test(c);
}

/** 读 localStorage 中 `baseKey.theme` 的颜色;非法或缺失返回 null */
export function readScopedColor(baseKey: ColorBaseKey, theme: Theme): string | null {
  const raw = localStorage.getItem(`${baseKey}.${theme}`);
  return isValidHexColor(raw) ? raw : null;
}

/** 写 localStorage 中 `baseKey.theme` 的颜色(仅在校验通过时写入) */
export function writeScopedColor(baseKey: ColorBaseKey, theme: Theme, color: string): void {
  if (!isValidHexColor(color)) return;
  localStorage.setItem(`${baseKey}.${theme}`, color);
}

/** 删除 localStorage 中 `baseKey.theme` 的颜色 */
export function clearScopedColor(baseKey: ColorBaseKey, theme: Theme): void {
  localStorage.removeItem(`${baseKey}.${theme}`);
}

/**
 * 迁移遗留单值(老版本无主题后缀的 `chatBgColor`/`userMsgColor`)到当前主题。
 *
 * 规则:若 `baseKey.theme` 尚未设置,但遗留单值存在且合法,则把遗留值复制到
 * `baseKey.theme`(归属到"当前主题"),另一主题保持未设(走主题默认色)。
 * 无论遗留值是否合法,迁移后都会删除遗留单值键,避免重复迁移。不丢数据。
 */
export function migrateLegacyScopedColor(baseKey: ColorBaseKey, theme: Theme): void {
  const scopedKey = `${baseKey}.${theme}`;
  if (localStorage.getItem(scopedKey) !== null) {
    // 已有按主题的值 —— 删除遗留单值(若存在)后直接返回,不覆盖
    localStorage.removeItem(baseKey);
    return;
  }
  const legacy = localStorage.getItem(baseKey);
  if (isValidHexColor(legacy)) {
    localStorage.setItem(scopedKey, legacy);
  }
  localStorage.removeItem(baseKey);
}

/**
 * 按 baseKey + theme 应用 CSS 变量;未设则移除变量(回退 LESS 默认)。
 */
export function applyScopedColor(baseKey: ColorBaseKey, theme: Theme): void {
  const color = readScopedColor(baseKey, theme);
  const vars = CSS_VARS[baseKey];
  const root = document.documentElement;
  if (color) {
    vars.forEach((v) => root.style.setProperty(v, color));
  } else {
    vars.forEach((v) => root.style.removeProperty(v));
  }
}

/** 便捷:应用聊天背景色(--bg-chat) */
export function applyChatBackground(theme: Theme): void {
  applyScopedColor('chatBgColor', theme);
}

/** 便捷:应用用户消息气泡色(--color-message-user-bg/-fade) */
export function applyUserMsgColor(theme: Theme): void {
  applyScopedColor('userMsgColor', theme);
}
