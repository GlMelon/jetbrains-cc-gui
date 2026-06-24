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
const modelRegistryPayloadJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/payload/ModelRegistryPayloadField.java');
const codexProtectedEnvKeyJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/protocol/CodexProtectedEnvKey.java');
const commonConstantsJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/common/CommonConstants.java');
const permissionDialogTimeoutSettingsJavaPath = resolve(__dirname, '../../src/main/java/com/github/claudecodegui/settings/PermissionDialogTimeoutSettings.java');

// C5:允许暴露给前端的 int 常量白名单(防泄露后端其他 int 实现细节)
const INT_CONSTANT_ALLOWLIST = [
  'DEFAULT_CONTEXT_WINDOW',
  'ONE_MILLION_CONTEXT_WINDOW',
  'DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS',
  'MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS',
  'MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS',
];

const isStubMode = process.argv.includes('--stub');

/**
 * 从 manifest 生成完整类型文件
 */
export function generateFromManifest(manifest) {
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

// ── Codex Protected Env Keys (business enum SSOT, A5) ──

export const CODEX_PROTECTED_ENV_KEY = {
${(manifest.codexProtectedEnvKey ?? []).map(k => `  ${k.name}: '${k.value}' as const,`).join('\n')}
} as const;

export type CodexProtectedEnvKey = typeof CODEX_PROTECTED_ENV_KEY[keyof typeof CODEX_PROTECTED_ENV_KEY];

// ── Int Constants (business defaults SSOT, C5) ──

${(manifest.intConstants ?? []).map(c => `export const ${c.name} = ${c.value} as const;`).join('\n')}
` + generatePayloadInterfaces(manifest.payloadSchemas);
}

/**
 * 生成 payload wire 接口(C1):每个 payloadSchemas entry → 一个 export interface。
 * interface 名 = PascalCase(key) + 'PayloadWire'(modelRegistry → ModelRegistryPayloadWire)。
 * 字段名 = wireKey,optional 字段带 ?,类型 = tsType。无 payloadSchemas 时返回空串(向后兼容)。
 */
function generatePayloadInterfaces(payloadSchemas) {
  if (!payloadSchemas || typeof payloadSchemas !== 'object') {
    return '';
  }
  const blocks = [];
  for (const [key, schema] of Object.entries(payloadSchemas)) {
    const fields = schema && Array.isArray(schema.fields) ? schema.fields : [];
    if (fields.length === 0) {
      continue;
    }
    const interfaceName = key.charAt(0).toUpperCase() + key.slice(1) + 'PayloadWire';
    const fieldLines = fields
      .map((f) => `  ${f.wireKey}${f.optional ? '?' : ''}: ${f.tsType};`)
      .join('\n');
    blocks.push(`export interface ${interfaceName} {\n${fieldLines}\n}`);
  }
  if (blocks.length === 0) {
    return '';
  }
  return `\n// ── Payload Schemas (wire field SSOT, C1) ──\n\n${blocks.join('\n\n')}\n`;
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

/**
 * 解析 Java payload 字段枚举源码(C1):匹配三参声明 NAME("wireKey","tsType",optional)。
 *
 * 与 parseEnumSource(单参 NAME("value"))正交:严格三参 regex 不匹配单参枚举,
 * 故可安全用于 ModelRegistryPayloadField.java 而不污染 UpstreamAction/DownstreamEvent 解析。
 * tsType 含空格(如 "readonly string[]")由 "([^"]*)" 整段捕获。
 *
 * @param {string} source Java 枚举源码文本
 * @param {string} label 用于告警定位的标签
 * @returns {Array<{name:string,wireKey:string,tsType:string,optional:boolean}>}
 */
export function parsePayloadFieldSource(source, label = '<payload-source>') {
  const fields = [];
  const fieldPattern = /^\s*([A-Z][A-Z0-9_]*)\("([^"]*)",\s*"([^"]*)",\s*(true|false)\)\s*,?/gm;
  let match;
  while ((match = fieldPattern.exec(source)) !== null) {
    fields.push({
      name: match[1],
      wireKey: match[2],
      tsType: match[3],
      optional: match[4] === 'true',
    });
  }
  return fields;
}

/**
 * 解析 Java int 常量源码(C5):匹配 `public static final int NAME = literal;`,
 * literal 可含 Java 数字分隔下划线(200_000),parseInt 前去下划线。allowlist 过滤,
 * 仅暴露白名单常量(防泄露后端其他 int 实现细节,前端只取所需 5 个默认值)。
 *
 * @param {string} source Java 源码文本
 * @param {string[]} allowlist 允许暴露的常量名白名单(默认空 = 不暴露任何)
 * @param {string} label 用于定位的标签
 * @returns {Array<{name:string,value:number}>}
 */
export function parseIntConstants(source, allowlist = [], label = '<int-source>') {
  const entries = [];
  const re = /public\s+static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*([0-9_]+)\s*;/g;
  let match;
  while ((match = re.exec(source)) !== null) {
    const name = match[1];
    if (allowlist.includes(name)) {
      entries.push({ name, value: parseInt(match[2].replace(/_/g, ''), 10) });
    }
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

/** 解析 payload 字段枚举声明 → {fields:[...]}(C1)。空解析抛错防静默漏项。 */
function parsePayloadSchema(javaPath) {
  const source = readFileSync(javaPath, 'utf-8');
  const fields = parsePayloadFieldSource(source, javaPath);
  if (fields.length === 0) {
    throw new Error(`No payload fields parsed from ${javaPath}`);
  }
  return { fields };
}

function generateManifestFromJavaSources() {
  return {
    upstream: parseJavaEnumProtocol(upstreamJavaPath),
    downstream: parseJavaEnumProtocol(downstreamJavaPath),
    permissionMode: parseJavaEnumProtocol(permissionModeJavaPath),
    reasoningEffort: parseJavaEnumProtocol(reasoningEffortJavaPath),
    providerType: parseJavaEnumProtocol(providerTypeJavaPath),
    codexProtectedEnvKey: parseJavaEnumProtocol(codexProtectedEnvKeyJavaPath),
    intConstants: [
      ...parseIntConstants(readFileSync(commonConstantsJavaPath, 'utf-8'), INT_CONSTANT_ALLOWLIST, 'CommonConstants'),
      ...parseIntConstants(readFileSync(permissionDialogTimeoutSettingsJavaPath, 'utf-8'), INT_CONSTANT_ALLOWLIST, 'PermissionDialogTimeoutSettings'),
    ],
    payloadSchemas: {
      modelRegistry: parsePayloadSchema(modelRegistryPayloadJavaPath),
    },
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const CODEX_PROTECTED_ENV_KEY: Record<string, string> = {};
export type CodexProtectedEnvKey = string;

// C5 int constants — stub 默认值与后端一致(从 Java 重新生成获取真值)
export const DEFAULT_CONTEXT_WINDOW = 200000 as const;
export const ONE_MILLION_CONTEXT_WINDOW = 1000000 as const;
export const DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS = 300 as const;
export const MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS = 30 as const;
export const MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS = 3600 as const;
`;
}

// ── Main ──

function main() {
  mkdirSync(dirname(outputPath), { recursive: true });

  let content;
  if (existsSync(upstreamJavaPath) && existsSync(downstreamJavaPath) && existsSync(permissionModeJavaPath) && existsSync(reasoningEffortJavaPath) && existsSync(providerTypeJavaPath) && existsSync(modelRegistryPayloadJavaPath) && existsSync(codexProtectedEnvKeyJavaPath) && existsSync(commonConstantsJavaPath) && existsSync(permissionDialogTimeoutSettingsJavaPath)) {
    const manifest = generateManifestFromJavaSources();
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf-8');
    content = generateFromManifest(manifest);
    console.log(`[generate-protocol-types] Generated from Java sources (${manifest.upstream.length} upstream, ${manifest.downstream.length} downstream, ${manifest.permissionMode?.length ?? 0} permissionMode, ${manifest.reasoningEffort?.length ?? 0} reasoningEffort, ${manifest.providerType?.length ?? 0} providerType, ${manifest.codexProtectedEnvKey?.length ?? 0} codexProtectedEnvKey, ${manifest.intConstants?.length ?? 0} intConstants, ${manifest.payloadSchemas?.modelRegistry?.fields?.length ?? 0} modelRegistry payload fields)`);
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
