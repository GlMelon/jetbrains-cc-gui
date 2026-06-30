const MAX_REVISIONS = 20;

export class RevisionStore {
  constructor(maxRevisions = MAX_REVISIONS) {
    this.maxRevisions = maxRevisions;
    this.revisions = new Map();
    this.latestRevision = 0;
  }

  put(revision, catalog) {
    const copy = JSON.parse(JSON.stringify(catalog ?? { tools: [] }));
    this.revisions.set(Number(revision), copy);
    this.latestRevision = Math.max(this.latestRevision, Number(revision));
    this.trim();
  }

  get(revision) {
    const key = Number(revision || this.latestRevision);
    return this.revisions.get(key) ?? { revision: key, tools: [] };
  }

  trim() {
    while (this.revisions.size > this.maxRevisions) {
      const first = this.revisions.keys().next().value;
      this.revisions.delete(first);
    }
  }
}
