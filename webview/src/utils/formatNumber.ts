/**
 * 数字格式化单点工具(总则四 · 复用 / D6)。
 *
 * 目前仅收口「容量简写」这一**逐字等价**的重复实现 —— 原散落于
 * `ModelRegistrySection.formatContext` 与 `TokenIndicator.formatMaxTokensK`。
 *
 * 注意:token **用量**展示(`ContextUsageDialog.formatTokens` / `TokenIndicator` chip 内
 * `formatTokens` / `UsageStatistics.formatNumber`)因精度(`toFixed(1)` / 整数优先)、大小写
 * (`k` / `K`)、档位(是否含 `B`)存在**有意的 UI 展示差异**,刻意不在本函数统一范围内 ——
 * 强行合并会改变用户可见显示,属可接受的展示差异(对齐 A7/A8/A9 展示分类降级惯例)。
 */

/**
 * 将容量/上限数值格式化为 K/M 简写(整数除法)。
 *
 * 用于 contextWindow / maxTokens 等「容量上限」展示:
 *   ≥1M → `${v / 1e6}M`(如 1000000 → "1M",2000000 → "2M")
 *   ≥1K → `${round(v / 1e3)}K`(如 200000 → "200K")
 *   <1K → 原值字符串(如 500 → "500")
 *
 * @param value 数值;`undefined` 时回退 `fallback`
 * @param fallback `value` 为 `undefined` 时的回退值(如 `DEFAULT_CONTEXT_WINDOW`)
 * @returns 简写字符串;`0` / `NaN` / 无值时返回 `''`(对齐原 `TokenIndicator.formatMaxTokensK` 的 falsy 守卫)
 */
export function formatCapacity(value: number | undefined, fallback?: number): string {
  const v = value ?? fallback;
  if (!v) return '';
  if (v >= 1_000_000) return `${v / 1_000_000}M`;
  if (v >= 1_000) return `${Math.round(v / 1_000)}K`;
  return `${v}`;
}
