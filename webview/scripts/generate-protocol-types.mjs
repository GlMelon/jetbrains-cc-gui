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
const permissionModeJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/PermissionMode.java');
const reasoningEffortJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/ReasoningEffort.java');
const providerTypeJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/session/runtime/ProviderType.java');

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

// ── Permission Mode (business enum SSOT, C2) ──

export const PERMISSION_MODE = {
${(manifest.permissionMode ?? []).map(m => `  ${m.name}: '${m.value}' as const,`).join('\n')}
} as const;

export type PermissionMode = typeof PERMISSION_MODE[keyof typeof PERMISSION_MODE];

// ── Reasoning Effort (business enum SSOT, C2) ──

export const REASONING_EFFORT = {
${(manifest.reasoningEffort ?? []).map(e => `  ${e.name}: '${e.value}' as const,`).join('\n')}
} as const;

export type ReasoningEffort = typeof REASONING_EFFORT[keyof typeof REASONING_EFFORT];

// ── Provider Type (business enum SSOT, C2/C9) ──

export const PROVIDER_TYPE = {
${(manifest.providerType ?? []).map(p => `  ${p.name}: '${p.value}' as const,`).join('\n')}
} as const;

export type ProviderType = typeof PROVIDER_TYPE[keyof typeof PROVIDER_TYPE];
`;
}

/**
 * 解析 Java 枚举源码为协议条目(纯函数,便于测试)。
 *
 * C8 漂移守门:严格 entryPattern 仅匹配 `NAME("value")` 单参格式。若枚举改用多参
 * (如 C2 未来加 desc:`NAME("value","desc")`),严格 regex 会静默漏解析。
 * 故用宽松启发(全大写名 + `(` + 引号)统计疑似常量声明数,与严格 regex 计数比对,
 * 不一致时显式 WARN,防止静默漏项。
 *
 * @param {string} source Java 源码文本
 * @param {string} label 用于告警定位的标签(通常为文件路径)
 * @returns {Array<{name:string,value:string}>} 严格 regex 解析出的条目(多参常量会被漏,WARN 提示)
 */
export function parseEnumSource(source, label = '<source>') {
  const entries = [];
  const entryPattern = /^\s*([A-Z0-9_]+)\("([^"]+)"\)\s*,?/gm;
  let match;

  while ((match = entryPattern.exec(source)) !== null) {
    entries.push({ name: match[1], value: match[2] });
  }

  // 宽松启发:匹配 `NAME("` 形态(全大写名 + ( + 引号),覆盖单参/多参。
  const loosePattern = /^\s*[A-Z][A-Z0-9_]*\(["']/gm;
  const looseCount = (source.match(loosePattern) || []).length;
  if (looseCount > entries.length) {
    console.warn(
      `[generate-protocol-types] ⚠️ DRIFT WARNING (${label}): 疑似 ${looseCount} 个枚举常量声明,但严格 regex 仅解析 ${entries.length} 个(差 ${looseCount - entries.length})。\n` +
      `  常见原因:枚举常量改用多参格式(如 NAME("value","desc")),严格 entryPattern 静默漏解析。\n` +
      `  请核对源码:更新 parseEnumSource 的 entryPattern,或长期走反射(manifest)主路径。`
    );
  }

  return entries;
}

function parseJavaEnumProtocol(javaPath) {
  const source = readFileSync(javaPath, 'utf-8');
  const entries = parseEnumSource(source, javaPath);
  if (entries.length === 0) {
    throw new Error(`No protocol enum entries parsed from ${javaPath}`);
  }
  return entries;
}

function generateManifestFromJavaSources() {
  return {
    upstream: parseJavaEnumProtocol(upstreamJavaPath),
    downstream: parseJavaEnumProtocol(downstreamJavaPath),
    permissionMode: parseJavaEnumProtocol(permissionModeJavaPath),
    reasoningEffort: parseJavaEnumProtocol(reasoningEffortJavaPath),
    providerType: parseJavaEnumProtocol(providerTypeJavaPath),
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const PERMISSION_MODE: Record<string, string> = {};
export type PermissionMode = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const REASONING_EFFORT: Record<string, string> = {};
export type ReasoningEffort = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const PROVIDER_TYPE: Record<string, string> = {};
export type ProviderType = string;
`;
}

// ── Main ──

function main() {
  mkdirSync(dirname(outputPath), { recursive: true });

  let content;
  if (existsSync(upstreamJavaPath) && existsSync(downstreamJavaPath) && existsSync(permissionModeJavaPath) && existsSync(reasoningEffortJavaPath) && existsSync(providerTypeJavaPath)) {
    const manifest = generateManifestFromJavaSources();
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf-8');
    content = generateFromManifest(manifest);
    console.log(`[generate-protocol-types] Generated from Java sources (${manifest.upstream.length} upstream, ${manifest.downstream.length} downstream, ${manifest.permissionMode?.length ?? 0} permissionMode, ${manifest.reasoningEffort?.length ?? 0} reasoningEffort, ${manifest.providerType?.length ?? 0} providerType)`);
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
}

// 仅在直接执行时运行(被 import 时不执行,便于单元测试 parseEnumSource)
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
