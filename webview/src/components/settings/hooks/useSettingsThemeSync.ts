// hooks/useSettingsThemeSync.ts
import { useState, useEffect, useCallback, useRef } from 'react';
import { applyDiffTheme, getStoredDiffTheme, type DiffThemeMode } from '../../../utils/diffTheme';
import {
  migrateLegacyScopedColor,
  readScopedColor,
  writeScopedColor,
  clearScopedColor,
  applyChatBackground,
  applyUserMsgColor,
  type Theme,
} from '../../../utils/appearanceColors';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import { applyChatBarThemeColor } from '../../../utils/chatBarTheme';

// Extend window type for IDE theme injection
declare global {
  interface Window {
    __INITIAL_IDE_THEME__?: 'light' | 'dark';
  }
}

/** 按主题分别存储的颜色(light/dark 各一份,空串表示未设 → 走主题默认) */
type ScopedColors = { light: string; dark: string };

/** 从 localStorage + Java 注入值解析初始实际主题(供 useState 初始化与遗留值迁移归属) */
function resolveInitialTheme(): Theme {
  const saved = localStorage.getItem('theme');
  if (saved === 'light' || saved === 'dark') return saved;
  const inj = window.__INITIAL_IDE_THEME__;
  if (inj === 'light' || inj === 'dark') return inj;
  return 'dark';
}

function readScopedColors(baseKey: 'chatBgColor' | 'userMsgColor' | 'chatBarColor'): ScopedColors {
  return {
    light: readScopedColor(baseKey, 'light') ?? '',
    dark: readScopedColor(baseKey, 'dark') ?? '',
  };
}

/** 归一化外观对象为快照字符串用于比较(空串/undefined 统一为缺省) */
function normalizeAppearanceForCompare(obj: {
  themePreference: string;
  fontSizeLevel: number;
  diffTheme: string;
  chatBgColor: ScopedColors;
  userMsgColor: ScopedColors;
  chatBarColor: ScopedColors;
}): string {
  return JSON.stringify({
    themePreference: obj.themePreference,
    fontSizeLevel: obj.fontSizeLevel,
    diffTheme: obj.diffTheme,
    chatBgColor: { light: obj.chatBgColor.light || undefined, dark: obj.chatBgColor.dark || undefined },
    userMsgColor: { light: obj.userMsgColor.light || undefined, dark: obj.userMsgColor.dark || undefined },
    chatBarColor: { light: obj.chatBarColor.light || undefined, dark: obj.chatBarColor.dark || undefined },
  });
}

export function useSettingsThemeSync() {
  const [themePreference, setThemePreference] = useState<'light' | 'dark' | 'system'>(() => {
    // Read theme preference from localStorage
    const savedTheme = localStorage.getItem('theme');
    if (savedTheme === 'light' || savedTheme === 'dark' || savedTheme === 'system') {
      return savedTheme;
    }
    return 'system'; // Default: follow IDE
  });

  // IDE theme state (prefer Java-injected initial theme, used to handle dynamic changes)
  const [ideTheme, setIdeTheme] = useState<'light' | 'dark' | null>(() => {
    // Check if Java has injected the initial theme
    const injectedTheme = window.__INITIAL_IDE_THEME__;
    if (injectedTheme === 'light' || injectedTheme === 'dark') {
      return injectedTheme;
    }
    return null;
  });

  // [按主题] 解析后的实际主题:system 模式跟随 ideTheme,否则取显式偏好
  const resolvedTheme: Theme = themePreference === 'system' ? (ideTheme ?? 'dark') : themePreference;

  // Font size level state (1-6, default is 2, i.e. 90%)
  const [fontSizeLevel, setFontSizeLevel] = useState<number>(() => {
    const savedLevel = localStorage.getItem('fontSizeLevel');
    const level = savedLevel ? parseInt(savedLevel, 10) : 2;
    return level >= 1 && level <= 6 ? level : 2;
  });

  // [按主题] 背景色/消息色分别存储。初始化时把遗留单值迁移到当前主题(仅一次,幂等)。
  const [chatBgColors, setChatBgColors] = useState<ScopedColors>(() => {
    const t = resolveInitialTheme();
    migrateLegacyScopedColor('chatBgColor', t);
    migrateLegacyScopedColor('userMsgColor', t);
    return readScopedColors('chatBgColor');
  });
  const [userMsgColors, setUserMsgColors] = useState<ScopedColors>(() => readScopedColors('userMsgColor'));
  const [chatBarColors, setChatBarColors] = useState<ScopedColors>(() => readScopedColors('chatBarColor'));

  // Diff theme configuration
  const [diffTheme, setDiffTheme] = useState<DiffThemeMode>(() => getStoredDiffTheme());

  // 对外暴露"当前 resolvedTheme"对应的颜色值;setter 仅写入当前主题的键
  const chatBgColor = chatBgColors[resolvedTheme];
  const userMsgColor = userMsgColors[resolvedTheme];
  const chatBarColor = chatBarColors[resolvedTheme];

  const setChatBgColor = useCallback((color: string) => {
    setChatBgColors((prev) => ({ ...prev, [resolvedTheme]: color }));
  }, [resolvedTheme]);
  const setUserMsgColor = useCallback((color: string) => {
    setUserMsgColors((prev) => ({ ...prev, [resolvedTheme]: color }));
  }, [resolvedTheme]);
  const setChatBarColor = useCallback((color: string) => {
    setChatBarColors((prev) => ({ ...prev, [resolvedTheme]: color }));
  }, [resolvedTheme]);

  // Theme switching handler (supports following IDE theme)
  useEffect(() => {
    const applyTheme = (preference: 'light' | 'dark' | 'system') => {
      if (preference === 'system') {
        // If following IDE, need to wait for IDE theme to load
        if (ideTheme === null) {
          return; // Wait for ideTheme to load
        }
        document.documentElement.setAttribute('data-theme', ideTheme);
      } else {
        // Explicit light/dark selection, apply immediately
        document.documentElement.setAttribute('data-theme', preference);
      }
    };

    applyTheme(themePreference);
    // Save to localStorage
    localStorage.setItem('theme', themePreference);
  }, [themePreference, ideTheme]);

  // Font size scaling handler
  useEffect(() => {
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

    // Apply to root element
    document.documentElement.style.setProperty('--font-scale', scale.toString());

    // Save to localStorage
    localStorage.setItem('fontSizeLevel', fontSizeLevel.toString());
  }, [fontSizeLevel]);

  // [按主题] 背景色 handler:写 localStorage + 应用当前主题颜色。
  // 依赖 resolvedTheme → 主题切换时重应用对应主题颜色(问题1核心修复)。
  useEffect(() => {
    const v = chatBgColors[resolvedTheme];
    if (v) {
      writeScopedColor('chatBgColor', resolvedTheme, v);
    } else {
      clearScopedColor('chatBgColor', resolvedTheme);
    }
    applyChatBackground(resolvedTheme);
  }, [resolvedTheme, chatBgColors]);

  // [按主题] 用户消息气泡色 handler
  useEffect(() => {
    const v = userMsgColors[resolvedTheme];
    if (v) {
      writeScopedColor('userMsgColor', resolvedTheme, v);
    } else {
      clearScopedColor('userMsgColor', resolvedTheme);
    }
    applyUserMsgColor(resolvedTheme);
  }, [resolvedTheme, userMsgColors]);

  // [按主题] chat bar 颜色 handler
  useEffect(() => {
    const v = chatBarColors[resolvedTheme];
    if (v) {
      writeScopedColor('chatBarColor', resolvedTheme, v);
    } else {
      clearScopedColor('chatBarColor', resolvedTheme);
    }
    applyChatBarThemeColor(v || '');
  }, [resolvedTheme, chatBarColors]);

  // Diff theme handler
  useEffect(() => {
    applyDiffTheme(diffTheme, ideTheme);
  }, [diffTheme, ideTheme, themePreference]);

  // [持久化] 防抖(400ms)落盘到 config.json,避免颜色选择器拖动时高频磁盘 IO。
  // 颜色拖动只触发即时 CSS + localStorage,落盘防抖合并。
  const lastHydrationRef = useRef<string | null>(null);
  useEffect(() => {
    const current = normalizeAppearanceForCompare({
      themePreference,
      fontSizeLevel,
      diffTheme,
      chatBgColor: chatBgColors,
      userMsgColor: userMsgColors,
      chatBarColor: chatBarColors,
    });
    // 冷缓存回灌触发的那一次 state 变更,其值等于回灌快照 → 跳过回写,防止 ABA 循环
    if (lastHydrationRef.current !== null && lastHydrationRef.current === current) {
      lastHydrationRef.current = null;
      return;
    }
    const handle = window.setTimeout(() => {
      // 走 typed sendAction,UPSTREAM.SET_APPEARANCE_CONFIG 来自 generated/protocol(SSOT);对象 payload 自动 stringify
      sendAction(UPSTREAM.SET_APPEARANCE_CONFIG, {
        themePreference,
        fontSizeLevel,
        diffTheme,
        chatBgColor: { light: chatBgColors.light || undefined, dark: chatBgColors.dark || undefined },
        userMsgColor: { light: userMsgColors.light || undefined, dark: userMsgColors.dark || undefined },
        chatBarColor: { light: chatBarColors.light || undefined, dark: chatBarColors.dark || undefined },
      });
    }, 400);
    return () => window.clearTimeout(handle);
  }, [themePreference, fontSizeLevel, diffTheme, chatBgColors, userMsgColors, chatBarColors]);

  // [冷缓存回灌] Java 注入 config.json 后,bootstrap 仅填充缺失键并派发该事件。
  // 从 localStorage 重建 state,并记录回灌快照供持久化 effect 跳过本次回写。
  useEffect(() => {
    const handler = () => {
      const tp = localStorage.getItem('theme');
      const newThemePreference: 'light' | 'dark' | 'system' =
        tp === 'light' || tp === 'dark' || tp === 'system' ? tp : 'system';
      const savedLevel = localStorage.getItem('fontSizeLevel');
      const newFontSize = savedLevel ? parseInt(savedLevel, 10) : 2;
      const newDiff = getStoredDiffTheme();
      const newChat = readScopedColors('chatBgColor');
      const newUserMsg = readScopedColors('userMsgColor');
      const newChatBar = readScopedColors('chatBarColor');

      lastHydrationRef.current = normalizeAppearanceForCompare({
        themePreference: newThemePreference,
        fontSizeLevel: newFontSize,
        diffTheme: newDiff,
        chatBgColor: newChat,
        userMsgColor: newUserMsg,
        chatBarColor: newChatBar,
      });

      setThemePreference(newThemePreference);
      setFontSizeLevel(newFontSize);
      setDiffTheme(newDiff);
      setChatBgColors(newChat);
      setUserMsgColors(newUserMsg);
      setChatBarColors(newChatBar);
    };
    window.addEventListener('appearance-config-applied', handler);
    return () => window.removeEventListener('appearance-config-applied', handler);
  }, []);

  return {
    themePreference,
    setThemePreference,
    ideTheme,
    setIdeTheme,
    resolvedTheme,
    fontSizeLevel,
    setFontSizeLevel,
    chatBgColor,
    setChatBgColor,
    userMsgColor,
    setUserMsgColor,
    chatBarColor,
    setChatBarColor,
    diffTheme,
    setDiffTheme,
  };
}
