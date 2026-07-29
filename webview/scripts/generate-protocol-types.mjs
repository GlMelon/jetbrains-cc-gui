/**
 * generate-protocol-types.mjs
 *
 * SSOT 主路径:构建时直接解析 Java 枚举源(UpstreamAction / DownstreamEvent /
 * PermissionMode / ReasoningEffort / ProviderType / CodexProtectedEnvKey 及
 * payload 字段声明、int 常量声明),生成 TypeScript 常量文件
 * webview/src/generated/protocol.ts,供前端 import { UPSTREAM, DOWNSTREAM, ... }
 * 引用并由 TypeScript 编译器校验拼写。
 *
 * protocol-manifest.json 为构建副产品(直读 Java 源后一并写出),供人工校验
 * 及 mjs regex 解析(C8 漂移守门)的反射交叉验证;非 Gradle generateProtocol
 * task 产出的依赖输入(该 task 默认禁用,见 build.gradle)。
 *
 * 使用方式:
 *   node scripts/generate-protocol-types.mjs          # 直读 Java 源生成(主路径)
 *   node scripts/generate-protocol-types.mjs --stub   # 无 Java 源时生成 stub
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const manifestPath = resolve(__dirname, '../src/generated/protocol-manifest.json');
const outputPath = resolve(__dirname, '../src/generated/protocol.ts');
const upstreamJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java',
);
const downstreamJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java',
);
const permissionModeJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/PermissionMode.java',
);
const reasoningEffortJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/ReasoningEffort.java',
);
const providerTypeJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/session/runtime/ProviderType.java',
);
const skillScopeJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/skill/SkillScopeType.java',
);
const skillFieldControlJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/skill/SkillFieldControl.java',
);
const modelRegistryPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/ModelRegistryPayloadField.java',
);
const webviewBootstrapPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/WebviewBootstrapPayloadField.java',
);
const historyExportFormatJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/HistoryExportFormat.java',
);
const codexHistoryPageModeJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/CodexHistoryPageMode.java',
);
const historyExportPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/HistoryExportPayloadField.java',
);
const historyCapabilitiesPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/HistoryCapabilitiesPayloadField.java',
);
const historyArchiveResultPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/HistoryArchiveResultPayloadField.java',
);
const codexHistoryPageRequestPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/CodexHistoryPageRequestPayloadField.java',
);
const codexHistoryPageInfoPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/CodexHistoryPageInfoPayloadField.java',
);
const codexHistoryPageErrorPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/CodexHistoryPageErrorPayloadField.java',
);
const skillDocumentFieldPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/SkillDocumentFieldPayloadField.java',
);
const skillDocumentResultPayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/SkillDocumentResultPayloadField.java',
);
const skillDocumentSavePayloadJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/payload/SkillDocumentSavePayloadField.java',
);
const codexProtectedEnvKeyJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/protocol/CodexProtectedEnvKey.java',
);
const versionActionJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/dependency/VersionAction.java',
);
const commonConstantsJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/common/CommonConstants.java',
);
const permissionDialogTimeoutSettingsJavaPath = resolve(
  __dirname,
  '../../src/main/java/com/github/claudecodegui/settings/PermissionDialogTimeoutSettings.java',
);

// C5:允许暴露给前端的 int 常量白名单(防泄露后端其他 int 实现细节)
const INT_CONSTANT_ALLOWLIST = [
  'DEFAULT_CONTEXT_WINDOW',
  'CODEX_HISTORY_PAGE_SIZE',
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
  return (
    `/**
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
${manifest.upstream.map((a) => `  ${a.name}: '${a.value}' as const,`).join('\n')}
} as const;

export type UpstreamAction = typeof UPSTREAM[keyof typeof UPSTREAM];

// ── Downstream Events (Java → Frontend) ──

export const DOWNSTREAM = {
${manifest.downstream.map((e) => `  ${e.name}: '${e.value}' as const,`).join('\n')}
} as const;

export type DownstreamEvent = typeof DOWNSTREAM[keyof typeof DOWNSTREAM];

// ── Permission Mode (business enum SSOT, C2) ──

export const PERMISSION_MODE = {
${(manifest.permissionMode ?? []).map((m) => `  ${m.name}: '${m.value}' as const,`).join('\n')}
} as const;

export type PermissionMode = typeof PERMISSION_MODE[keyof typeof PERMISSION_MODE];

// ── Reasoning Effort (business enum SSOT, C2) ──

export const REASONING_EFFORT = {
${(manifest.reasoningEffort ?? []).map((e) => `  ${e.name}: '${e.value}' as const,`).join('\n')}
} as const;

export type ReasoningEffort = typeof REASONING_EFFORT[keyof typeof REASONING_EFFORT];

// ── Provider Type (business enum SSOT, C2/C9) ──

export const PROVIDER_TYPE = {
${(manifest.providerType ?? []).map((p) => `  ${p.name}: '${p.value}' as const,`).join('\n')}
} as const;

export type ProviderType = typeof PROVIDER_TYPE[keyof typeof PROVIDER_TYPE];

// ── Skill Scope (business enum SSOT) ──

export const SKILL_SCOPE = {
${(manifest.skillScope ?? []).map((s) => `  ${s.name}: '${s.value}' as const,`).join('\n')}
} as const;

export type SkillScope = typeof SKILL_SCOPE[keyof typeof SKILL_SCOPE];

// ── Skill Editor Field Control (business enum SSOT) ──

export const SKILL_FIELD_CONTROL = {
${(manifest.skillFieldControl ?? []).map((c) => `  ${c.name}: '${c.value}' as const,`).join('\n')}
} as const;

export type SkillFieldControl = typeof SKILL_FIELD_CONTROL[keyof typeof SKILL_FIELD_CONTROL];

// ── History Export Format (business enum SSOT) ──

export const HISTORY_EXPORT_FORMAT = {
${(manifest.historyExportFormat ?? []).map((f) => `  ${f.name}: '${f.value}' as const,`).join('\n')}
} as const;

export type HistoryExportFormat = typeof HISTORY_EXPORT_FORMAT[keyof typeof HISTORY_EXPORT_FORMAT];

// ── Codex History Page Mode (business enum SSOT) ──

export const CODEX_HISTORY_PAGE_MODE = {
${(manifest.codexHistoryPageMode ?? []).map((a) => `  ${a.name}: '${a.value}' as const,`).join('\n')}
} as const;

export type CodexHistoryPageMode = typeof CODEX_HISTORY_PAGE_MODE[keyof typeof CODEX_HISTORY_PAGE_MODE];

// ── Codex Protected Env Keys (business enum SSOT, A5) ──

export const CODEX_PROTECTED_ENV_KEY = {
${(manifest.codexProtectedEnvKey ?? []).map((k) => `  ${k.name}: '${k.value}' as const,`).join('\n')}
} as const;

export type CodexProtectedEnvKey = typeof CODEX_PROTECTED_ENV_KEY[keyof typeof CODEX_PROTECTED_ENV_KEY];

// ── Version Action (business enum SSOT, A6) ──

export const VERSION_ACTION = {
${(manifest.versionAction ?? []).map((a) => `  ${a.name}: '${a.value}' as const,`).join('\n')}
} as const;

export type VersionAction = typeof VERSION_ACTION[keyof typeof VERSION_ACTION];

// ── Int Constants (business defaults SSOT, C5) ──

${(manifest.intConstants ?? []).map((c) => `export const ${c.name} = ${c.value} as const;`).join('\n')}
` + generatePayloadInterfaces(manifest.payloadSchemas)
  );
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
 * C8 漂移守门:entryPattern 匹配 `NAME("value",...)` 格式(支持单参/多参)。
 * 提取第一个引号参数作为协议值;多参枚举(如 ProviderType 的 value,cliCommand,cliCommandWindows)
 * 均可正确解析,无需特殊处理。
 *
 * @param {string} source Java 源码文本
 * @param {string} label 用于告警定位的标签(通常为文件路径)
 * @returns {Array<{name:string,value:string}>} 解析出的条目
 */
export function parseEnumSource(source, label = '<source>') {
  const entries = [];
  const entryPattern = /^\s*([A-Z0-9_]+)\("([^"]+)"/gm;
  let match;

  while ((match = entryPattern.exec(source)) !== null) {
    entries.push({ name: match[1], value: match[2] });
  }

  // 宽松启发:匹配 `NAME("` 形态(全大写名 + ( + 引号),覆盖单参/多参。
  const loosePattern = /^\s*[A-Z][A-Z0-9_]*\(["']/gm;
  const looseCount = (source.match(loosePattern) || []).length;
  if (looseCount > entries.length) {
    console.warn(
      `[generate-protocol-types] ⚠️ DRIFT WARNING (${label}): 疑似 ${looseCount} 个枚举常量声明,但 entryPattern 仅解析 ${entries.length} 个(差 ${looseCount - entries.length})。\n` +
        `  常见原因:枚举常量未实现 ProtocolValue 接口,或首参非字符串类型。\n` +
        `  请核对源码:确认枚举是否正确声明为 NAME("string-value",...) 格式。`,
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
    skillScope: parseJavaEnumProtocol(skillScopeJavaPath),
    skillFieldControl: parseJavaEnumProtocol(skillFieldControlJavaPath),
    historyExportFormat: parseJavaEnumProtocol(historyExportFormatJavaPath),
    codexHistoryPageMode: parseJavaEnumProtocol(codexHistoryPageModeJavaPath),
    codexProtectedEnvKey: parseJavaEnumProtocol(codexProtectedEnvKeyJavaPath),
    versionAction: parseJavaEnumProtocol(versionActionJavaPath),
    intConstants: [
      ...parseIntConstants(
        readFileSync(commonConstantsJavaPath, 'utf-8'),
        INT_CONSTANT_ALLOWLIST,
        'CommonConstants',
      ),
      ...parseIntConstants(
        readFileSync(permissionDialogTimeoutSettingsJavaPath, 'utf-8'),
        INT_CONSTANT_ALLOWLIST,
        'PermissionDialogTimeoutSettings',
      ),
    ],
    payloadSchemas: {
      modelRegistry: parsePayloadSchema(modelRegistryPayloadJavaPath),
      webviewBootstrap: parsePayloadSchema(webviewBootstrapPayloadJavaPath),
      historyExport: parsePayloadSchema(historyExportPayloadJavaPath),
      historyCapabilities: parsePayloadSchema(historyCapabilitiesPayloadJavaPath),
      historyArchiveResult: parsePayloadSchema(historyArchiveResultPayloadJavaPath),
      codexHistoryPageRequest: parsePayloadSchema(codexHistoryPageRequestPayloadJavaPath),
      codexHistoryPageInfo: parsePayloadSchema(codexHistoryPageInfoPayloadJavaPath),
      codexHistoryPageError: parsePayloadSchema(codexHistoryPageErrorPayloadJavaPath),
      skillDocumentField: parsePayloadSchema(skillDocumentFieldPayloadJavaPath),
      skillDocumentResult: parsePayloadSchema(skillDocumentResultPayloadJavaPath),
      skillDocumentSave: parsePayloadSchema(skillDocumentSavePayloadJavaPath),
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
export const SKILL_SCOPE: Record<string, string> = {};
export type SkillScope = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const SKILL_FIELD_CONTROL: Record<string, string> = {};
export type SkillFieldControl = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const HISTORY_EXPORT_FORMAT: Record<string, string> = {};
export type HistoryExportFormat = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const CODEX_PROTECTED_ENV_KEY: Record<string, string> = {};
export type CodexProtectedEnvKey = string;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const VERSION_ACTION: Record<string, string> = {};
export type VersionAction = string;

// C5 int constants — stub 默认值与后端一致(从 Java 重新生成获取真值)
export const DEFAULT_CONTEXT_WINDOW = 200000 as const;
export const ONE_MILLION_CONTEXT_WINDOW = 1000000 as const;
export const DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS = 300 as const;
export const MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS = 30 as const;
export const MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS = 3600 as const;

export interface HistoryExportPayloadWire {
  success: boolean;
  sessionId?: string;
  title?: string;
  format?: HistoryExportFormat;
  fileName?: string;
  mimeType?: string;
  content?: string;
  truncated?: boolean;
  exportedMessageCount?: number;
  omittedMessageCount?: number;
  maxMessageCount?: number;
  maxUtf8Bytes?: number;
  error?: string;
}

export interface HistoryCapabilitiesPayloadWire {
  canDelete: boolean;
  canArchive: boolean;
}

export interface HistoryArchiveResultPayloadWire {
  success: boolean;
  requestedSessionIds: readonly string[];
  archivedSessionIds: readonly string[];
  failedSessionIds: readonly string[];
}
`;
}

// ── Main ──

function main() {
  mkdirSync(dirname(outputPath), { recursive: true });

  let content;
  if (
    existsSync(upstreamJavaPath) &&
    existsSync(downstreamJavaPath) &&
    existsSync(permissionModeJavaPath) &&
    existsSync(reasoningEffortJavaPath) &&
    existsSync(providerTypeJavaPath) &&
    existsSync(skillScopeJavaPath) &&
    existsSync(skillFieldControlJavaPath) &&
    existsSync(modelRegistryPayloadJavaPath) &&
    existsSync(webviewBootstrapPayloadJavaPath) &&
    existsSync(historyExportFormatJavaPath) &&
    existsSync(codexHistoryPageModeJavaPath) &&
    existsSync(historyExportPayloadJavaPath) &&
    existsSync(historyCapabilitiesPayloadJavaPath) &&
    existsSync(historyArchiveResultPayloadJavaPath) &&
    existsSync(codexHistoryPageRequestPayloadJavaPath) &&
    existsSync(codexHistoryPageInfoPayloadJavaPath) &&
    existsSync(codexHistoryPageErrorPayloadJavaPath) &&
    existsSync(skillDocumentFieldPayloadJavaPath) &&
    existsSync(skillDocumentResultPayloadJavaPath) &&
    existsSync(skillDocumentSavePayloadJavaPath) &&
    existsSync(codexProtectedEnvKeyJavaPath) &&
    existsSync(versionActionJavaPath) &&
    existsSync(commonConstantsJavaPath) &&
    existsSync(permissionDialogTimeoutSettingsJavaPath)
  ) {
    const manifest = generateManifestFromJavaSources();
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf-8');
    content = generateFromManifest(manifest);
    console.log(
      `[generate-protocol-types] Generated from Java sources (${manifest.upstream.length} upstream, ${manifest.downstream.length} downstream, ${manifest.permissionMode?.length ?? 0} permissionMode, ${manifest.reasoningEffort?.length ?? 0} reasoningEffort, ${manifest.providerType?.length ?? 0} providerType, ${manifest.skillScope?.length ?? 0} skillScope, ${manifest.skillFieldControl?.length ?? 0} skillFieldControl, ${manifest.historyExportFormat?.length ?? 0} historyExportFormat, ${manifest.codexProtectedEnvKey?.length ?? 0} codexProtectedEnvKey, ${manifest.versionAction?.length ?? 0} versionAction, ${manifest.intConstants?.length ?? 0} intConstants, ${manifest.payloadSchemas?.modelRegistry?.fields?.length ?? 0} modelRegistry payload fields, ${manifest.payloadSchemas?.webviewBootstrap?.fields?.length ?? 0} webviewBootstrap payload fields, ${manifest.payloadSchemas?.historyExport?.fields?.length ?? 0} historyExport payload fields, ${manifest.payloadSchemas?.historyCapabilities?.fields?.length ?? 0} historyCapabilities payload fields, ${manifest.payloadSchemas?.historyArchiveResult?.fields?.length ?? 0} historyArchiveResult payload fields, ${manifest.payloadSchemas?.skillDocumentField?.fields?.length ?? 0} skillDocumentField payload fields, ${manifest.payloadSchemas?.skillDocumentResult?.fields?.length ?? 0} skillDocumentResult payload fields, ${manifest.payloadSchemas?.skillDocumentSave?.fields?.length ?? 0} skillDocumentSave payload fields)`,
    );
  } else if (existsSync(manifestPath)) {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8'));
    if (existsSync(webviewBootstrapPayloadJavaPath)) {
      manifest.payloadSchemas = {
        ...(manifest.payloadSchemas ?? {}),
        webviewBootstrap: parsePayloadSchema(webviewBootstrapPayloadJavaPath),
      };
      writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf-8');
    }
    content = generateFromManifest(manifest);
    console.log(
      `[generate-protocol-types] Generated from manifest (${manifest.upstream?.length ?? 0} upstream, ${manifest.downstream?.length ?? 0} downstream, ${manifest.payloadSchemas?.webviewBootstrap?.fields?.length ?? 0} webviewBootstrap payload fields)`,
    );
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
