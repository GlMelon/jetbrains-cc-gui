import { describe, it, expect } from 'vitest';
import { summarizeChangelog } from './changelogSummary';

describe('summarizeChangelog', () => {
  it('空文本 → 空结构(total=0, 无分区, 无散落)', () => {
    const s = summarizeChangelog('');
    expect(s.sections).toEqual([]);
    expect(s.loose).toEqual([]);
    expect(s.stats).toEqual([]);
    expect(s.total).toBe(0);
  });

  it('纯空白/换行文本 → 空结构', () => {
    const s = summarizeChangelog('   \n\n  \n');
    expect(s.sections).toEqual([]);
    expect(s.total).toBe(0);
  });

  it('分区计数(en:✨ Features 3项 + 🐛 Fixes 2项 → sections=2, total=5)', () => {
    const text = `✨ Features
- A
- B
- C
🐛 Fixes
- D
- E`;
    const s = summarizeChangelog(text);
    expect(s.sections).toHaveLength(2);
    expect(s.sections[0].kind).toBe('feature');
    expect(s.sections[0].items).toHaveLength(3);
    expect(s.sections[1].kind).toBe('fix');
    expect(s.sections[1].items).toHaveLength(2);
    expect(s.total).toBe(5);
  });

  it('stats 按 kind 聚合 item 数(feature=1, fix=2)', () => {
    const text = `✨ Features
- A
🐛 Fixes
- B
- C`;
    const s = summarizeChangelog(text);
    const byKind = Object.fromEntries(s.stats.map((x) => [x.kind, x.count]));
    expect(byKind.feature).toBe(1);
    expect(byKind.fix).toBe(2);
  });

  it('stats 合并同 kind 多分区(两个 feature 分区 → count 相加)', () => {
    const text = `✨ Features
- A
✨ More Features
- B
- C`;
    const s = summarizeChangelog(text);
    const byKind = Object.fromEntries(s.stats.map((x) => [x.kind, x.count]));
    expect(byKind.feature).toBe(3);
  });

  it('散落 - 项(分区前)归入 loose,不计入分区 items', () => {
    const text = `- orphan
✨ Features
- A`;
    const s = summarizeChangelog(text);
    expect(s.loose).toHaveLength(1);
    expect(s.sections[0].items).toHaveLength(1);
    expect(s.total).toBe(1); // 仅分区内的 A 计入 total
  });

  it('散落普通段落(无 - 前缀,无 emoji)归入 loose', () => {
    const s = summarizeChangelog('some intro paragraph');
    expect(s.loose).toHaveLength(1);
  });

  it('中文版本(emoji 同,标题中文:✨ 新功能 / 🐛 修复)kind 正确识别', () => {
    const text = `✨ 新功能
- 新增 A
🐛 修复
- 修复 B`;
    const s = summarizeChangelog(text);
    expect(s.sections[0].kind).toBe('feature');
    expect(s.sections[0].head).toContain('新功能');
    expect(s.sections[1].kind).toBe('fix');
  });

  it('item 内联格式化(**bold** → <strong>, `code` → <code>)', () => {
    const text = `✨ Features
- Add **bold** and \`code\``;
    const s = summarizeChangelog(text);
    expect(s.sections[0].items[0]).toContain('<strong>bold</strong>');
    expect(s.sections[0].items[0]).toContain('<code>code</code>');
  });

  it('⚡️ Performance(perf 变体,含 variation selector)识别为 perf', () => {
    const text = `⚡️ Performance
- faster`;
    const s = summarizeChangelog(text);
    expect(s.sections[0].kind).toBe('perf');
  });

  it('head 保留 emoji + 标题原文(用于分区标题展示)', () => {
    const text = `✨ Features
- A`;
    const s = summarizeChangelog(text);
    expect(s.sections[0].head).toBe('✨ Features');
  });

  it('未知 emoji 分区(🎉)kind=other,仍计入 total', () => {
    const text = `🎉 Misc
- something`;
    const s = summarizeChangelog(text);
    expect(s.sections[0].kind).toBe('other');
    expect(s.total).toBe(1);
  });

  it('纯函数幂等:同输入两次调用结构相等', () => {
    const text = `✨ Features
- A
🐛 Fixes
- B`;
    const a = summarizeChangelog(text);
    const b = summarizeChangelog(text);
    expect(a).toEqual(b);
  });
});
