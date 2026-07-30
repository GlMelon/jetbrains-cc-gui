/**
 * Changelog 文本 → 结构化 summary(What's New Hero 视图消费)。
 *
 * 与 ChangelogDialog.renderChangelogMarkdown 共用分区识别规则(emoji 前缀行开启分区,
 * `- ` 列表项归入分区)。summary 返回结构化数据(sections/stats),Hero 视图直接渲染分区
 * 列表 + 统计胶囊;旧 renderChangelogMarkdown 暂留作降级兜底。
 */

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function formatInline(text: string): string {
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`(.+?)`/g, '<code>$1</code>');
}



export interface ChangelogSection {
  /** 分区语义:feature/fix/improve/perf/other(取自 SECTION_KIND)。 */
  kind: string;
  /** 标题原文(含 emoji 前缀,如 "✨ Features"/"✨ 新功能");视图渲染时需 escapeHtml。 */
  head: string;
  /** 列表项(formatInline 后的 HTML 片段,视图可直接 dangerouslySetInnerHTML)。 */
  items: string[];
}

export interface ChangelogStat {
  kind: string;
  count: number;
}

export interface ChangelogSummary {
  /** 有序分区列表(保留 changelog 文本中的出现顺序)。 */
  sections: ChangelogSection[];
  /** 游离内容:无分区前缀的 `- ` 项或普通段落(formatInline 后 HTML)。 */
  loose: string[];
  /** 按 kind 聚合的 item 计数(合并同 kind 多分区)。 */
  stats: ChangelogStat[];
  /** 分区内 item 总数(不含游离内容)。 */
  total: number;
}

const EMPTY_SUMMARY: ChangelogSummary = { sections: [], loose: [], stats: [], total: 0 };

/**
 * 把单语言 changelog 文本解析为结构化 summary。
 *
 * 规则:emoji 前缀行开启分区;`- ` 列表项归入当前分区(无分区则归 loose);
 * 其余非空行:有分区并入分区 items,否则归 loose。空/纯空白文本返回空结构。
 */
export function summarizeChangelog(text: string): ChangelogSummary {
  if (!text || !text.trim()) return EMPTY_SUMMARY;

  const SECTION_KIND: Record<string, string> = {
    '✨': 'feature',
    '🐛': 'fix',
    '🔧': 'improve',
    '⚡': 'perf',
    '⚡️': 'perf',
  };

  const SECTION_EMOJI = /^[✨🐛🔧🎉🚀💡⚡️🔥📦🛠️]/;

  const sections: ChangelogSection[] = [];
  const loose: string[] = [];
  let current: ChangelogSection | null = null;

  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith('- ')) {
      const item = formatInline(line.substring(2));
      if (current) {
        current.items.push(item);
      } else {
        loose.push(item);
      }
      continue;
    }

    // 用 codePointAt 提取完整 emoji(无 u flag 正则 match[0] 只返回 high surrogate,
    // 会让 SECTION_KIND lookup 失败误归类 other —— 既有 bug,codePointAt 取首码点修正)。
    const firstCp = line.codePointAt(0);
    if (firstCp !== undefined && SECTION_EMOJI.test(line)) {
      const emoji = String.fromCodePoint(firstCp);
      current = {
        kind: SECTION_KIND[emoji] ?? 'other',
        head: line,
        items: [],
      };
      sections.push(current);
      continue;
    }

    // 段落或 P\d 优先级标签:已有分区则并入列表,否则作为游离段落。
    if (current) {
      current.items.push(formatInline(line));
    } else {
      loose.push(formatInline(line));
    }
  }

  const countByKind = new Map<string, number>();
  for (const s of sections) {
    countByKind.set(s.kind, (countByKind.get(s.kind) ?? 0) + s.items.length);
  }
  const stats: ChangelogStat[] = Array.from(countByKind.entries()).map(([kind, count]) => ({
    kind,
    count,
  }));
  const total = sections.reduce((sum, s) => sum + s.items.length, 0);

  return { sections, loose, stats, total };
}
