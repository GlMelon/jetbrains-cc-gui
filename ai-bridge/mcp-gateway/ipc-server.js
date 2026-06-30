import http from 'node:http';
import { requireToken } from './security.js';
import { ServerSupervisor } from './server-supervisor.js';
import { buildCatalog } from './tool-catalog.js';
import { ToolRouter } from './tool-router.js';

export class IpcServer {
  constructor({ token, revisionStore, healthStore, supervisors, startedAt }) {
    this.token = token;
    this.revisionStore = revisionStore;
    this.healthStore = healthStore;
    this.supervisors = supervisors;
    this.startedAt = startedAt;
    this.latestRevision = 0;
    this.server = http.createServer((req, res) => this.handle(req, res));
  }

  listen() {
    return new Promise((resolve) => {
      this.server.listen(0, '127.0.0.1', () => resolve(this.server.address().port));
    });
  }

  close() {
    for (const supervisor of this.supervisors.values()) {
      supervisor.stop();
    }
    this.server.close();
  }

  async handle(req, res) {
    if (!requireToken(req, this.token)) {
      this.write(res, 401, { error: 'unauthorized' });
      return;
    }
    try {
      if (req.method === 'POST' && req.url === '/snapshot') {
        const body = await readJson(req);
        await this.applySnapshot(body);
        this.write(res, 200, this.status());
        return;
      }
      if (req.method === 'GET' && req.url === '/status') {
        this.write(res, 200, this.status());
        return;
      }
      if (req.method === 'GET' && req.url?.startsWith('/runtime/tools/list')) {
        const url = new URL(req.url, 'http://127.0.0.1');
        const revision = Number(url.searchParams.get('revision') || this.latestRevision);
        const catalog = this.revisionStore.get(revision);
        this.write(res, 200, { tools: catalog.tools ?? [] });
        return;
      }
      if (req.method === 'POST' && req.url === '/runtime/tools/call') {
        const body = await readJson(req);
        const router = new ToolRouter(this.supervisors);
        const result = await router.call(body.name, body.arguments ?? {}, body.revision);
        this.write(res, 200, result);
        return;
      }
      if (req.method === 'POST' && req.url === '/stop') {
        this.write(res, 200, { ok: true });
        setTimeout(() => {
          this.close();
          process.exit(0);
        }, 20);
        return;
      }
      this.write(res, 404, { error: 'not found' });
    } catch (error) {
      this.write(res, 500, { error: error?.message ?? String(error) });
    }
  }

  async applySnapshot(snapshot) {
    const revision = Number(snapshot.revision || 0);
    this.latestRevision = Math.max(this.latestRevision, revision);
    const desired = new Map();
    for (const spec of snapshot.servers ?? []) {
      if (!spec.enabled) continue;
      const key = `${spec.sourceProvider}:${spec.serverId}`;
      desired.set(key, spec);
      const existing = this.supervisors.get(key);
      const nextHash = JSON.stringify(spec);
      if (!existing || existing.configHash !== nextHash) {
        existing?.stop();
        const supervisor = new ServerSupervisor(spec, this.healthStore);
        supervisor.configHash = nextHash;
        this.supervisors.set(key, supervisor);
      }
    }
    for (const key of [...this.supervisors.keys()]) {
      if (!desired.has(key)) {
        this.supervisors.get(key)?.stop();
        this.supervisors.delete(key);
        this.healthStore.remove(key);
      }
    }
    const refreshes = [...this.supervisors.values()].map((supervisor) => supervisor.refresh());
    await Promise.allSettled(refreshes);
    this.revisionStore.put(revision, buildCatalog(revision, this.supervisors));
  }

  status() {
    return this.healthStore.snapshot(this.latestRevision, Date.now() - this.startedAt);
  }

  write(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  }
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}
