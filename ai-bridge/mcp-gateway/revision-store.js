// @ts-check
/**
 * 工具目录历史版本存储:保留最近 N 个 revision 的 catalog 快照,
 * 供 ToolRouter 按 revision 回查(避免旧请求拿到新 catalog 后错配工具)。
 */

/** @type {number} */
const MAX_REVISIONS = 20;

/**
 * 一份 catalog 快照的最小形状;字段在历史/占位数据中可能缺失。
 * @typedef {{ revision?: number; tools?: unknown[] }} CatalogSnapshot
 */

/**
 * 版本化工具目录存储;超过 maxRevisions 时按插入顺序淘汰最早一项。
 */
export class RevisionStore {
  /**
   * @param {number} [maxRevisions] 保留的最大版本数,默认 20
   */
  constructor(maxRevisions = MAX_REVISIONS) {
    /** @type {number} */
    this.maxRevisions = maxRevisions;
    /** @type {Map<number, CatalogSnapshot>} */
    this.revisions = new Map();
    /** @type {number} */
    this.latestRevision = 0;
  }

  /**
   * 写入一份 catalog 快照(深拷贝隔离),更新 latestRevision 并按需 trim。
   *
   * @param {number} revision 版本号
   * @param {CatalogSnapshot | null | undefined} catalog 快照(null/undefined 当作 { tools: [] })
   * @returns {void}
   */
  put(revision, catalog) {
    const copy = JSON.parse(JSON.stringify(catalog ?? { tools: [] }));
    this.revisions.set(Number(revision), copy);
    this.latestRevision = Math.max(this.latestRevision, Number(revision));
    this.trim();
  }

  /**
   * 读取指定 revision 的快照;revision 缺省时取最新;精确版本已被淘汰时回退到现存最旧快照
   * (revision 字段保持实际版本,供调用方比对);store 为空时返回占位空快照。
   *
   * @param {number | undefined} [revision] 版本号
   * @returns {CatalogSnapshot}
   */
  get(revision) {
    const key = Number(revision || this.latestRevision);
    let snapshot = this.revisions.get(key);
    if (!snapshot && this.revisions.size > 0) {
      // 精确版本已被 MAX_REVISIONS 淘汰时回退到现存最旧快照(与请求版本最接近),
      // 不再静默返空工具;返回快照的 revision 字段保持实际版本,供调用方比对。
      const oldestKey = this.revisions.keys().next().value;
      if (oldestKey !== undefined) {
        snapshot = this.revisions.get(oldestKey);
      }
    }
    const resolved = snapshot ?? { revision: key, tools: [] };
    // 项10:put 已深拷贝隔离存储,但 get 返回内部引用会让调用方修改污染 store(下次 get 拿到被改的)。
    // 返回深拷贝保持快照不变性。
    return JSON.parse(JSON.stringify(resolved));
  }

  /**
   * 超出 maxRevisions 时按插入顺序淘汰最早一项。
   *
   * @returns {void}
   */
  trim() {
    while (this.revisions.size > this.maxRevisions) {
      const first = this.revisions.keys().next().value;
      // 理论不可达:循环条件已保证 size > 0,first 必有值;守卫仅为消除 number | undefined 的窄化
      if (first === undefined) break;
      this.revisions.delete(first);
    }
  }
}
