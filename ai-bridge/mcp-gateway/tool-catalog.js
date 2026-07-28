// @ts-check
/**
 * 汇总所有 supervisor 当前的工具列表,打上统一 revision 形成一份全量 catalog。
 */

/**
 * supervisor 提供给 catalog 汇总的最小形状(至少含 tools 数组)。
 * @typedef {{ tools?: unknown[] }} CatalogSupervisor
 */

/**
 * 汇总所有 supervisor 的 tools,生成一份带 revision 的全量 catalog。
 *
 * @param {number} revision 本次 catalog 的版本号
 * @param {Map<unknown, CatalogSupervisor>} supervisors key 任意、value 至少含 tools 数组
 * @returns {{ revision: number; tools: unknown[] }}
 */
export function buildCatalog(revision, supervisors) {
  /** @type {unknown[]} */
  const tools = [];
  for (const supervisor of supervisors.values()) {
    tools.push(...(supervisor.tools ?? []));
  }
  return { revision: Number(revision), tools };
}
