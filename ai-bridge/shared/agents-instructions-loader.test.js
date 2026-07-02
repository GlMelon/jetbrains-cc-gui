import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import path from 'path';
import os from 'os';
import {
  AGENTS_FILE_NAMES,
  MAX_AGENTS_MD_BYTES,
  findAgentsFileInDir,
  readAgentsFile,
  findGitRoot,
  collectProjectAgentsInstructions,
  resolveProviderGlobalHome,
  collectAgentsInstructionsForProvider,
} from './agents-instructions-loader.js';

// ---------- fixture helpers ----------

function mkdtemp(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function writeFile(dir, name, content) {
  const p = path.join(dir, name);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content, 'utf8');
  return p;
}

function mkdirGitRoot(prefix = 'agents-git-') {
  const root = mkdtemp(prefix);
  fs.mkdirSync(path.join(root, '.git'), { recursive: true });
  return root;
}

function cleanup(...dirs) {
  for (const d of dirs) {
    try { fs.rmSync(d, { recursive: true, force: true }); } catch (_) { /* ignore */ }
  }
}

// ---------- constants ----------

test('AGENTS_FILE_NAMES priority order is AGENTS.override.md > AGENTS.md > CLAUDE.md', () => {
  assert.deepEqual(AGENTS_FILE_NAMES, ['AGENTS.override.md', 'AGENTS.md', 'CLAUDE.md']);
});

test('MAX_AGENTS_MD_BYTES is 32KB', () => {
  assert.equal(MAX_AGENTS_MD_BYTES, 32 * 1024);
});

// ---------- findAgentsFileInDir ----------

test('findAgentsFileInDir: AGENTS.md beats CLAUDE.md', () => {
  const dir = mkdtemp('agents-prio-');
  try {
    writeFile(dir, 'AGENTS.md', 'plain');
    writeFile(dir, 'CLAUDE.md', 'claude');
    assert.equal(path.basename(findAgentsFileInDir(dir)), 'AGENTS.md');
  } finally { cleanup(dir); }
});

test('findAgentsFileInDir: AGENTS.override.md beats AGENTS.md', () => {
  const dir = mkdtemp('agents-prio-');
  try {
    writeFile(dir, 'AGENTS.override.md', 'override');
    writeFile(dir, 'AGENTS.md', 'plain');
    assert.equal(path.basename(findAgentsFileInDir(dir)), 'AGENTS.override.md');
  } finally { cleanup(dir); }
});

test('findAgentsFileInDir: returns null when no agents file exists', () => {
  const dir = mkdtemp('agents-empty-');
  try {
    assert.equal(findAgentsFileInDir(dir), null);
  } finally { cleanup(dir); }
});

test('findAgentsFileInDir: skips empty (zero-byte) files', () => {
  const dir = mkdtemp('agents-zero-');
  try {
    writeFile(dir, 'AGENTS.md', '');
    writeFile(dir, 'CLAUDE.md', 'claude content');
    assert.equal(path.basename(findAgentsFileInDir(dir)), 'CLAUDE.md');
  } finally { cleanup(dir); }
});

// ---------- readAgentsFile ----------

test('readAgentsFile: reads full content when under limit', () => {
  const dir = mkdtemp('agents-read-');
  try {
    const p = writeFile(dir, 'AGENTS.md', 'hello world');
    assert.equal(readAgentsFile(p), 'hello world');
  } finally { cleanup(dir); }
});

test('readAgentsFile: truncates at MAX_AGENTS_MD_BYTES', () => {
  const dir = mkdtemp('agents-trunc-');
  try {
    const p = writeFile(dir, 'AGENTS.md', 'x'.repeat(MAX_AGENTS_MD_BYTES + 100));
    const out = readAgentsFile(p);
    assert.equal(out.length, MAX_AGENTS_MD_BYTES);
    assert.equal(out, 'x'.repeat(MAX_AGENTS_MD_BYTES));
  } finally { cleanup(dir); }
});

test('readAgentsFile: returns empty string for missing file', () => {
  const missing = path.join(os.tmpdir(), 'agents-nonexistent-' + process.pid + '.md');
  assert.equal(readAgentsFile(missing), '');
});

// ---------- findGitRoot ----------

test('findGitRoot: walks up to the nearest .git directory', () => {
  const root = mkdirGitRoot();
  try {
    const sub = path.join(root, 'a', 'b', 'c');
    fs.mkdirSync(sub, { recursive: true });
    assert.equal(findGitRoot(sub), root);
  } finally { cleanup(root); }
});

test('findGitRoot: finds the nearest .git, not a farther one', () => {
  const outer = mkdirGitRoot('agents-outer-');
  try {
    const inner = path.join(outer, 'inner');
    fs.mkdirSync(inner, { recursive: true });
    fs.mkdirSync(path.join(inner, '.git'), { recursive: true });
    const sub = path.join(inner, 'work');
    fs.mkdirSync(sub, { recursive: true });
    assert.equal(findGitRoot(sub), inner);
  } finally { cleanup(outer); }
});

test('findGitRoot: returns null when no .git exists upwards', (t) => {
  const deep = mkdtemp('agents-nogit-');
  try {
    const result = findGitRoot(deep);
    // os.tmpdir() 祖先链可能含 .git(如用户 home 被初始化为 repo)— 此环境无法测 null 路径,优雅 skip。
    // 干净文件系统(如 CI 的 /tmp)才会真正断言 null。
    if (result !== null) {
      t.skip(`skipped: ancestor of tmpdir contains .git (${result})`);
      return;
    }
    assert.equal(result, null);
  } finally { cleanup(deep); }
});

// ---------- collectProjectAgentsInstructions ----------

test('collectProjectAgentsInstructions: collects git root -> cwd in root-to-leaf order', () => {
  const root = mkdirGitRoot();
  try {
    writeFile(root, 'AGENTS.md', 'ROOT INSTRUCTIONS');
    fs.mkdirSync(path.join(root, 'pkg'), { recursive: true });
    writeFile(path.join(root, 'pkg'), 'AGENTS.md', 'PKG INSTRUCTIONS');
    const sub = path.join(root, 'pkg', 'mod');
    fs.mkdirSync(sub, { recursive: true });
    const out = collectProjectAgentsInstructions(sub);
    assert.ok(out.indexOf('ROOT INSTRUCTIONS') < out.indexOf('PKG INSTRUCTIONS'),
      'root instructions must appear before nested instructions');
    assert.match(out, /ROOT INSTRUCTIONS/);
    assert.match(out, /PKG INSTRUCTIONS/);
  } finally { cleanup(root); }
});

test('collectProjectAgentsInstructions: returns empty string when no AGENTS.md found', () => {
  const root = mkdirGitRoot();
  try {
    const sub = path.join(root, 'empty');
    fs.mkdirSync(sub, { recursive: true });
    assert.equal(collectProjectAgentsInstructions(sub), '');
  } finally { cleanup(root); }
});

test('collectProjectAgentsInstructions: does not include global instructions section', () => {
  const root = mkdirGitRoot();
  try {
    writeFile(root, 'AGENTS.md', 'ONLY PROJECT');
    const out = collectProjectAgentsInstructions(root);
    assert.equal(out.includes('Global Instructions'), false);
  } finally { cleanup(root); }
});

// ---------- resolveProviderGlobalHome ----------

test('resolveProviderGlobalHome: codex honors CODEX_HOME env when set', () => {
  const old = process.env.CODEX_HOME;
  process.env.CODEX_HOME = path.join(os.tmpdir(), 'custom-codex-home');
  try {
    assert.equal(resolveProviderGlobalHome('codex'), process.env.CODEX_HOME);
  } finally {
    if (old === undefined) delete process.env.CODEX_HOME; else process.env.CODEX_HOME = old;
  }
});

test('resolveProviderGlobalHome: codex falls back to ~/.codex when env unset', () => {
  const old = process.env.CODEX_HOME;
  delete process.env.CODEX_HOME;
  try {
    assert.ok(resolveProviderGlobalHome('codex').replace(/\\/g, '/').endsWith('/.codex'));
  } finally {
    if (old !== undefined) process.env.CODEX_HOME = old;
  }
});

test('resolveProviderGlobalHome: claude resolves to a .claude directory', () => {
  assert.ok(resolveProviderGlobalHome('claude').replace(/\\/g, '/').endsWith('/.claude'));
});

test('resolveProviderGlobalHome: opencode resolves to an opencode config directory', () => {
  assert.ok(resolveProviderGlobalHome('opencode').toLowerCase().includes('opencode'));
});

test('resolveProviderGlobalHome: unknown provider returns null', () => {
  assert.equal(resolveProviderGlobalHome('unknown-provider'), null);
});

// ---------- collectAgentsInstructionsForProvider ----------

test('collectAgentsInstructionsForProvider: combines global + project, global first', () => {
  const root = mkdirGitRoot();
  const globalHome = mkdtemp('agents-global-');
  try {
    writeFile(root, 'AGENTS.md', 'PROJECT RULES');
    writeFile(globalHome, 'AGENTS.md', 'GLOBAL RULES');
    const out = collectAgentsInstructionsForProvider('claude', root, { globalHomeDir: globalHome });
    assert.ok(out.indexOf('GLOBAL RULES') < out.indexOf('PROJECT RULES'),
      'global instructions must appear before project instructions');
  } finally { cleanup(root, globalHome); }
});

test('collectAgentsInstructionsForProvider: project-only when no global file', () => {
  const root = mkdirGitRoot();
  const globalHome = mkdtemp('agents-global-empty-');
  try {
    writeFile(root, 'AGENTS.md', 'PROJECT ONLY');
    const out = collectAgentsInstructionsForProvider('opencode', root, { globalHomeDir: globalHome });
    assert.match(out, /PROJECT ONLY/);
    assert.equal(out.includes('Global Instructions'), false);
  } finally { cleanup(root, globalHome); }
});

test('collectAgentsInstructionsForProvider: empty when neither global nor project has AGENTS.md', () => {
  const root = mkdirGitRoot();
  const globalHome = mkdtemp('agents-global-empty2-');
  try {
    const out = collectAgentsInstructionsForProvider('claude', root, { globalHomeDir: globalHome });
    assert.equal(out, '');
  } finally { cleanup(root, globalHome); }
});

test('collectAgentsInstructionsForProvider: null cwd returns empty', () => {
  assert.equal(collectAgentsInstructionsForProvider('claude', null), '');
});
