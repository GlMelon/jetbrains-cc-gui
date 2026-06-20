/**
 * generate-protocol-types.mjs
 *
 * 读取 protocol-manifest.json (由 Gradle generateProtocol task 生成),
 * 输出 TypeScript 常量文件 webview/src/generated/protocol.ts。
 *
 * 前端代码通过 import { UPSTREAM, DOWNSTREAM } from '../generated/protocol'
 * 引用协议常量,TypeScript 编译器自动校验拼写。
 *
 * 使用方式:
 *   node scripts/generate-protocol-types.mjs          # 从 manifest 生成
 *   node scripts/generate-protocol-types.mjs --stub   # 无 manifest 时生成 stub
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const manifestPath = resolve(__dirname, '../src/generated/protocol-manifest.json');
const outputPath = resolve(__dirname, '../src/generated/protocol.ts');
const upstreamJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java');
const downstreamJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java');

const isStubMode = process.argv.includes('--stub');

/**
 * 从 manifest 生成完整类型文件
 */
function generateFromManifest(manifest) {
  return `/**
 * ⚠️ AUTO-GENERATED — DO NOT EDIT MANUALLY
 *
 * Source of Truth: Java protocol enums
 *   - com.github.claudecodegui.protocol.UpstreamAction
 *   - com.github.claudecodegui.protocol.DownstreamEvent
 *
 * Generator: webview/scripts/generate-protocol-types.mjs
 * Update:   edit Java enum(s), then rebuild webview (npm run build regenerates this file)
 */

// ── Upstream Actions (Frontend → Java) ──

export const UPSTREAM = {
${manifest.upstream.map(a => `  ${a.name}: '${a.value}' as const,`).join('\n')}
} as const;

export type UpstreamAction = typeof UPSTREAM[keyof typeof UPSTREAM];

// ── Downstream Events (Java → Frontend) ──

export const DOWNSTREAM = {
${manifest.downstream.map(e => `  ${e.name}: '${e.value}' as const,`).join('\n')}
} as const;

export type DownstreamEvent = typeof DOWNSTREAM[keyof typeof DOWNSTREAM];
`;
}

function parseJavaEnumProtocol(javaPath) {
  const source = readFileSync(javaPath, 'utf-8');
  const entries = [];
  const entryPattern = /^\s*([A-Z0-9_]+)\("([^"]+)"\)\s*,?/gm;
  let match;

  while ((match = entryPattern.exec(source)) !== null) {
    entries.push({ name: match[1], value: match[2] });
  }

  if (entries.length === 0) {
    throw new Error(`No protocol enum entries parsed from ${javaPath}`);
  }

  return entries;
}

function generateManifestFromJavaSources() {
  return {
    upstream: parseJavaEnumProtocol(upstreamJavaPath),
    downstream: parseJavaEnumProtocol(downstreamJavaPath),
  };
}

/**
 * 无 manifest 时生成 stub (避免前端构建失败)
 */
function generateStub() {
  return `/**
 * ⚠️ STUB — Java protocol enum sources not found.
 *
 * Only generated when UpstreamAction.java / DownstreamEvent.java are absent
 * (e.g. webview built standalone without the Java backend). Build from the
 * project root to regenerate full types from Java enums.
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const UPSTREAM: Record<string, string> = {};
export type UpstreamAction = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const DOWNSTREAM: Record<string, string> = {};
export type DownstreamEvent = string;
`;
}

// ── Main ──

mkdirSync(dirname(outputPath), { recursive: true });

let content;
if (existsSync(upstreamJavaPath) && existsSync(downstreamJavaPath)) {
  const manifest = generateManifestFromJavaSources();
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf-8');
  content = generateFromManifest(manifest);
  console.log(`[generate-protocol-types] Generated from Java sources (${manifest.upstream.length} upstream, ${manifest.downstream.length} downstream)`);
} else if (existsSync(manifestPath)) {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8'));
  content = generateFromManifest(manifest);
  console.log(`[generate-protocol-types] Generated from manifest (${manifest.upstream?.length ?? 0} upstream, ${manifest.downstream?.length ?? 0} downstream)`);
} else if (isStubMode) {
  content = generateStub();
  console.log('[generate-protocol-types] Generated stub (manifest not found, use --stub)');
} else {
  console.error(`[generate-protocol-types] ERROR: manifest not found at ${manifestPath}`);
  console.error('  Run "gradle generateProtocol" first, or use --stub for a fallback.');
  process.exit(1);
}

writeFileSync(outputPath, content, 'utf-8');
console.log(`[generate-protocol-types] Output: ${outputPath}`);
