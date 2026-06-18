import { afterEach, describe, expect, it } from 'vitest';
import {
  isValidHexColor,
  readScopedColor,
  writeScopedColor,
  clearScopedColor,
  migrateLegacyScopedColor,
  applyChatBackground,
  applyUserMsgColor,
} from './appearanceColors';

/**
 * appearanceColors 是「按主题分别记忆背景色/消息色」(Issue 1)的单一事实源。
 * 这些纯函数被 useThemeInit(主聊天运行时)与 useSettingsThemeSync(设置面板)共用,
 * 保证两处的迁移、读写、CSS 变量应用逻辑一致。这里锁定其契约,防止回归。
 */
describe('appearanceColors', () => {
  afterEach(() => {
    localStorage.clear();
    const root = document.documentElement;
    ['--bg-chat', '--color-message-user-bg', '--color-message-user-fade'].forEach((v) =>
      root.style.removeProperty(v),
    );
  });

  describe('isValidHexColor', () => {
    it('接受 6 位 hex(大小写)', () => {
      expect(isValidHexColor('#2b2d30')).toBe(true);
      expect(isValidHexColor('#FFFFFF')).toBe(true);
      expect(isValidHexColor('#005fb8')).toBe(true);
    });

    it('拒绝 3 位 / 无 # / 空 / null / undefined', () => {
      expect(isValidHexColor('#fff')).toBe(false);
      expect(isValidHexColor('2b2d30')).toBe(false);
      expect(isValidHexColor('')).toBe(false);
      expect(isValidHexColor(null)).toBe(false);
      expect(isValidHexColor(undefined)).toBe(false);
    });
  });

  describe('按主题读写/清除', () => {
    it('亮/暗主题各自独立存储与读取', () => {
      writeScopedColor('chatBgColor', 'dark', '#2b2d30');
      writeScopedColor('chatBgColor', 'light', '#fafafa');

      expect(readScopedColor('chatBgColor', 'dark')).toBe('#2b2d30');
      expect(readScopedColor('chatBgColor', 'light')).toBe('#fafafa');
      expect(localStorage.getItem('chatBgColor.dark')).toBe('#2b2d30');
      expect(localStorage.getItem('chatBgColor.light')).toBe('#fafafa');
    });

    it('writeScopedColor 忽略非法 hex(不写入)', () => {
      writeScopedColor('chatBgColor', 'dark', 'not-a-color');
      expect(readScopedColor('chatBgColor', 'dark')).toBeNull();
      expect(localStorage.getItem('chatBgColor.dark')).toBeNull();
    });

    it('readScopedColor 对缺失或非法值返回 null', () => {
      expect(readScopedColor('userMsgColor', 'dark')).toBeNull();
      localStorage.setItem('userMsgColor.dark', 'bad-value');
      expect(readScopedColor('userMsgColor', 'dark')).toBeNull();
    });

    it('clearScopedColor 删除对应主题键', () => {
      writeScopedColor('userMsgColor', 'dark', '#005fb8');
      clearScopedColor('userMsgColor', 'dark');
      expect(readScopedColor('userMsgColor', 'dark')).toBeNull();
      expect(localStorage.getItem('userMsgColor.dark')).toBeNull();
    });
  });

  describe('migrateLegacyScopedColor(遗留单值迁移)', () => {
    it('把遗留单值迁移到「当前主题」键并删除遗留键', () => {
      localStorage.setItem('chatBgColor', '#2b2d30'); // 遗留单值
      migrateLegacyScopedColor('chatBgColor', 'dark');

      expect(readScopedColor('chatBgColor', 'dark')).toBe('#2b2d30');
      expect(localStorage.getItem('chatBgColor')).toBeNull(); // 遗留键已删
    });

    it('scoped 键已存在时不覆盖,但仍清理遗留键', () => {
      localStorage.setItem('chatBgColor', '#aaaaaa'); // 遗留
      localStorage.setItem('chatBgColor.dark', '#2b2d30'); // 已有 scoped 值

      migrateLegacyScopedColor('chatBgColor', 'dark');

      expect(readScopedColor('chatBgColor', 'dark')).toBe('#2b2d30'); // 保持不变
      expect(localStorage.getItem('chatBgColor')).toBeNull(); // 遗留键仍被删除
    });

    it('遗留值非法时不复制,但仍删除遗留键(不残留)', () => {
      localStorage.setItem('chatBgColor', 'not-hex');
      migrateLegacyScopedColor('chatBgColor', 'dark');

      expect(readScopedColor('chatBgColor', 'dark')).toBeNull();
      expect(localStorage.getItem('chatBgColor')).toBeNull();
    });

    it('无任何值时为幂等 no-op', () => {
      migrateLegacyScopedColor('chatBgColor', 'dark');
      expect(readScopedColor('chatBgColor', 'dark')).toBeNull();
    });
  });

  describe('applyChatBackground / applyUserMsgColor(CSS 变量应用)', () => {
    it('设置 chatBgColor 后写入 --bg-chat', () => {
      writeScopedColor('chatBgColor', 'dark', '#2b2d30');
      applyChatBackground('dark');
      expect(document.documentElement.style.getPropertyValue('--bg-chat')).toBe('#2b2d30');
    });

    it('该主题未设 chatBgColor 时移除 --bg-chat(回退 LESS 默认)', () => {
      // 模拟此前由其它主题写入的残留值
      document.documentElement.style.setProperty('--bg-chat', '#ffffff');
      applyChatBackground('dark'); // dark 未设 → 应移除
      expect(document.documentElement.style.getPropertyValue('--bg-chat')).toBe('');
    });

    it('userMsgColor 同时写入 bg 与 fade 两个变量', () => {
      writeScopedColor('userMsgColor', 'dark', '#005fb8');
      applyUserMsgColor('dark');
      expect(document.documentElement.style.getPropertyValue('--color-message-user-bg')).toBe('#005fb8');
      expect(document.documentElement.style.getPropertyValue('--color-message-user-fade')).toBe('#005fb8');
    });

    it('应用按主题隔离:dark 的值不泄漏到 light 的应用', () => {
      writeScopedColor('chatBgColor', 'dark', '#2b2d30');
      applyChatBackground('light'); // light 未设 → 移除
      expect(document.documentElement.style.getPropertyValue('--bg-chat')).toBe('');

      applyChatBackground('dark'); // dark 有值 → 写入
      expect(document.documentElement.style.getPropertyValue('--bg-chat')).toBe('#2b2d30');
    });
  });
});
