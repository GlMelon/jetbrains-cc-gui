export class HealthStore {
  constructor() {
    this.servers = new Map();
  }

  set(key, value) {
    this.servers.set(key, { ...value });
  }

  remove(key) {
    this.servers.delete(key);
  }

  snapshot(revision, uptimeMs) {
    return {
      revision,
      uptimeMs,
      servers: [...this.servers.values()],
    };
  }
}
