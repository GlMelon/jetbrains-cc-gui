import { sendAction, subscribeEvent } from '../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../generated/protocol';
import { useEffect, useState } from 'react';
import { registerLegacyAlias } from '../bridge';
import { migrateLegacyScopedColor, applyChatBackground, applyUserMsgColor, type Theme } from '../utils/appearanceColors';

/**
 * 解析当前实际主题:优先读全局 data-theme(已被解析为亮/暗),回退 Java 注入值,再回退 dark。
 * data-theme 由 useThemeInit(system 模式)与 useSettingsThemeSync(显式模式)共同维护,
 * 是全局真相,因此颜色按主题应用时以它为准。
 */
function resolveCurrentTheme(): Theme {
  const attr = document.documentElement.getAttribute('data-theme');
  if (attr === 'light' || attr === 'dark') return attr;
  const injected = window.__INITIAL_IDE_THEME__;
  if (injected === 'light' || injected === 'dark') return injected;
  return 'dark';
}

/**
 * Manages IDE theme initialization and synchronization.
 * Handles font scaling, background color, and theme mode detection.
 */
export function useThemeInit() {
  // IDE theme state - prefer initial theme injected by Java
  const [ideTheme, setIdeTheme] = useState<'light' | 'dark' | null>(() => {
    const injectedTheme = window.__INITIAL_IDE_THEME__;
    if (injectedTheme === 'light' || injectedTheme === 'dark') {
      return injectedTheme;
    }
    return null;
  });

  // Initialize theme and font scaling
  useEffect(() => {
    // [归一化] onIdeThemeReceived → theme.received / onIdeThemeChanged → theme.changed
    registerLegacyAlias('onIdeThemeReceived', DOWNSTREAM.THEME_RECEIVED);
    subscribeEvent(DOWNSTREAM.THEME_RECEIVED, (jsonStr) => {
      try {
        const themeData = JSON.parse(jsonStr as string);
        const theme = themeData.isDark ? 'dark' : 'light';
        setIdeTheme(theme);
      } catch {
        // Failed to parse IDE theme response
      }
    });

    registerLegacyAlias('onIdeThemeChanged', DOWNSTREAM.THEME_CHANGED);
    subscribeEvent(DOWNSTREAM.THEME_CHANGED, (jsonStr) => {
      try {
        const themeData = JSON.parse(jsonStr as string);
        const theme = themeData.isDark ? 'dark' : 'light';
        setIdeTheme(theme);
      } catch {
        // Failed to parse IDE theme change
      }
    });

    // Initialize font scaling
    const savedLevel = localStorage.getItem('fontSizeLevel');
    const level = savedLevel ? parseInt(savedLevel, 10) : 2; // Default level 2 (90%)
    const fontSizeLevel = (level >= 1 && level <= 6) ? level : 2;

    // Map level to scale ratio
    const fontSizeMap: Record<number, number> = {
      1: 0.8,   // 80%
      2: 0.9,   // 90% (default)
      3: 1.0,   // 100%
      4: 1.1,   // 110%
      5: 1.2,   // 120%
      6: 1.4,   // 140%
    };
    const scale = fontSizeMap[fontSizeLevel] || 1.0;
    document.documentElement.style.setProperty('--font-scale', scale.toString());

    // [按主题] 先把遗留单值颜色迁移到当前主题,再应用当前主题保存的颜色
    const mountTheme = resolveCurrentTheme();
    migrateLegacyScopedColor('chatBgColor', mountTheme);
    migrateLegacyScopedColor('userMsgColor', mountTheme);
    applyChatBackground(mountTheme);
    applyUserMsgColor(mountTheme);

    // Apply the user's explicit theme choice (light/dark) first
    const savedTheme = localStorage.getItem('theme');

    // Check if there's an initial theme injected by Java
    const injectedTheme = window.__INITIAL_IDE_THEME__;

    // Request IDE theme (with retry mechanism)
    let retryCount = 0;
    const MAX_RETRIES = 20; // Max 20 retries (2 seconds)

    const requestIdeTheme = () => {
      if (window.sendToJava) {
        sendAction(UPSTREAM.GET_IDE_THEME);
      } else {
        retryCount++;
        if (retryCount < MAX_RETRIES) {
          setTimeout(requestIdeTheme, 100);
        } else {
          // If in Follow IDE mode and unable to get IDE theme, use injected theme or dark as fallback
          if (savedTheme === null || savedTheme === 'system') {
            const fallback = injectedTheme || 'dark';
            setIdeTheme(fallback as 'light' | 'dark');
          }
        }
      }
    };

    // Delay 100ms before requesting, giving the bridge time to initialize
    setTimeout(requestIdeTheme, 100);
  }, []);

  // Re-apply theme when IDE theme changes (if user chose "Follow IDE")
  useEffect(() => {
    const savedTheme = localStorage.getItem('theme');

    // Only process after ideTheme has been loaded
    if (ideTheme === null) {
      return;
    }

    // If user selected "Follow IDE" mode
    if (savedTheme === null || savedTheme === 'system') {
      document.documentElement.setAttribute('data-theme', ideTheme);
    }

    // [按主题] 主题变化后重应用当前主题对应的颜色。data-theme 已反映解析后的主题;
    // 显式模式下 ideTheme 变化不影响 data-theme,颜色保持不变。
    const currentTheme = resolveCurrentTheme();
    applyChatBackground(currentTheme);
    applyUserMsgColor(currentTheme);
  }, [ideTheme]);

  // [冷缓存回灌] Java 注入 config.json 后,bootstrap 回灌 localStorage 并派发该事件;重应用当前主题颜色
  useEffect(() => {
    const handler = () => {
      const t = resolveCurrentTheme();
      applyChatBackground(t);
      applyUserMsgColor(t);
    };
    window.addEventListener('appearance-config-applied', handler);
    return () => window.removeEventListener('appearance-config-applied', handler);
  }, []);

  return { ideTheme };
}
