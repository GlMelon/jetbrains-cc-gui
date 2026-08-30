/**
 * check-event-literals.mjs
 *
 * A8 前端协议第二真相源收敛 — 字面量漂移检测守门。
 *
 * 背景:
 *   webview/src/generated/protocol.ts 的 DOWNSTREAM 常量是下行事件名的唯一真相源(SSOT)。
 *   任何在生产代码中以字符串字面量形式('usage.update' / "stream.start" / `model.selection`)
 *   写出 DOWNSTREAM value 的行为都构成第二真相源,会产生前后端协议隐蔽漂移。
 *
 * 守门动作:
 *   1. 解析 protocol.ts 中 DOWNSTREAM 块的所有 {KEY: 'value'} 对,建立 value → KEY 映射。
 *   2. 扫描 webview/src 下所有生产代码(.ts/.tsx),排除生成产物(generated/)、测试(__tests__/,
 *      *.test.*, *.spec.*)、以及其他非协议消费目录(node_modules/ dist/ build/)。
 *   3. 对每处字符串字面量(单引号 / 双引号 / 无插值的反引号),若其值等于某个 DOWNSTREAM value,
 *      即判定为漂移,报告文件:行号 + 字面量 + 建议替换为 DOWNSTREAM.KEY 引用。
 *   4. 发现任何漂移以非零码退出,便于 CI 直接 gate。
 *
 * 使用方式:
 *   node webview/scripts/check-event-literals.mjs          # 扫描默认目录(webview/src)
 *   node webview/scripts/check-event-literals.mjs --quiet   # 仅输出漂移条目,省略尾部 summary
 *
 * 退出码:
 *   0 — 无漂移
 *   1 — 发现漂移(CI 失败)
 *   2 — 脚本自身错误(如 protocol.ts 解析失败)
 *
 * 设计约束:
 *   - 零外部依赖,纯 Node ESM,CI 可直接 node 调用。
 *   - 与 generate-protocol-types.mjs 同目录,沿用既有 codegen 约定。
 *   - 不解析 TypeScript AST(避免依赖 typescript 包);用足够保守的 regex。
 *     保守误报好过漏报——若出现合法字面量误报,在脚本顶部 ALLOWLIST 显式放行并注明理由。
 */

import { readFileSync, readdirSync, statSync } from 'fs';
import { parseEnumSource } from './generate-protocol-types.mjs';
import { resolve, join, extname, relative, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..'); // webview/
const SRC_DIR = resolve(ROOT, 'src');
const PROTOCOL_PATH = resolve(SRC_DIR, 'generated/protocol.ts');
const MANIFEST_PATH = resolve(SRC_DIR, 'generated/protocol-manifest.json');
const UPSTREAM_JAVA_PATH = resolve(
  ROOT,
  '../src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java',
);
const DOWNSTREAM_JAVA_PATH = resolve(
  ROOT,
  '../src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java',
);

const isQuiet = process.argv.includes('--quiet');

// ── 扫描范围控制 ──────────────────────────────────────────────────────────

const TARGET_EXTS = new Set(['.ts', '.tsx']);

/** 相对于 webview/src 的目录basename,命中即整个目录跳过。 */
const EXCLUDE_DIRS = new Set([
  'generated', // 生成产物(protocol.ts 本身,合法持有 value 字面量)
  '__tests__', // 单测目录,字面量硬编码合理
  '__mocks__',
  'node_modules',
  'dist',
  'build',
  '.vite',
]);

/** 文件名 pattern 命中即跳过(单测、snapshot 等)。 */
const EXCLUDE_FILE_PATTERNS = [
  /\.test\.[tj]sx?$/,
  /\.spec\.[tj]sx?$/,
  /\.d\.ts$/,
  /__snapshots__/,
];

/**
 * 合法漂移 allowlist。
 *
 * 每个条目:{ file: <repo-relative 路径前缀匹配>, value: <DOWNSTREAM value>, reason: <理由> }。
 * 命中即不报告。新增条目必须注明理由,并在 A8 后续阶段持续清理直至清空。
 *
 * 当前条目:无。runtimeProviderCapabilities.ts(注册中心职能)与
 * DependencySection/index.tsx(消费侧 dispatch)的字面量均已收敛为 DOWNSTREAM.* 引用。
 */
const ALLOWLIST = [
  // 暂无条目。A8 已完成 DependencySection 消费侧收敛:原 2 处 dispatch 字面量
  // ('dependency.update_available' / 'dependency.versions_loaded') 已替换为
  // DOWNSTREAM.DEPENDENCY_UPDATE_AVAILABLE / DOWNSTREAM.DEPENDENCY_VERSIONS_LOADED 引用。
  // 新增条目必须注明理由,并在 A8 后续阶段持续清理直至清空。
];

// ── 1. 解析 protocol.ts DOWNSTREAM ────────────────────────────────────────

export function parseGeneratedProtocolConstant(protocolSrc, constantName) {
  const blockRe = new RegExp(
    `export\\s+const\\s+${constantName}\\s*=\\s*\\{([\\s\\S]*?)\\}\\s*as\\s+const\\s*;`,
  );
  const blockMatch = protocolSrc.match(blockRe);
  if (!blockMatch) {
    throw new Error(`未在 protocol.ts 中找到 ${constantName} 常量块`);
  }
  const entryRe = /^\s*([A-Z][A-Z0-9_]*)\s*:\s*'([^']*)'\s+as\s+const\s*,?/gm;
  const entries = [];
  let match;
  while ((match = entryRe.exec(blockMatch[1])) !== null) {
    entries.push({ name: match[1], value: match[2] });
  }
  if (entries.length === 0) {
    throw new Error(`${constantName} 块解析出 0 个条目,regex 可能过时`);
  }
  return entries;
}

export function compareProtocolEntries(reference, actual, referenceLabel, actualLabel) {
  const errors = [];
  const buildMap = (entries, label) => {
    const map = new Map();
    for (const entry of entries) {
      if (!entry || typeof entry.name !== 'string' || typeof entry.value !== 'string') {
        errors.push(`${label}: invalid entry ${JSON.stringify(entry)}`);
        continue;
      }
      if (map.has(entry.name)) {
        errors.push(`${label}: duplicate name ${entry.name}`);
        continue;
      }
      map.set(entry.name, entry.value);
    }
    return map;
  };

  const referenceMap = buildMap(reference, referenceLabel);
  const actualMap = buildMap(actual, actualLabel);
  for (const [name, value] of referenceMap) {
    if (!actualMap.has(name)) {
      errors.push(`${actualLabel}: missing ${name}=${JSON.stringify(value)}`);
    } else if (actualMap.get(name) !== value) {
      errors.push(
        `${actualLabel}: value drift for ${name}, expected ${JSON.stringify(value)}, actual ${JSON.stringify(actualMap.get(name))}`,
      );
    }
  }
  for (const [name, value] of actualMap) {
    if (!referenceMap.has(name)) {
      errors.push(`${actualLabel}: unexpected ${name}=${JSON.stringify(value)}`);
    }
  }
  return errors;
}

function parseDownstream(protocolSrc) {
  const entries = parseGeneratedProtocolConstant(protocolSrc, 'DOWNSTREAM');
  const valueToKeys = new Map();
  for (const entry of entries) {
    if (!valueToKeys.has(entry.value)) valueToKeys.set(entry.value, []);
    valueToKeys.get(entry.value).push(entry.name);
  }
  return { valueToKeys, count: entries.length };
}

/**
 * MCP 业务词表(P2-MCP 收敛):这些生成常量的 value(如 'stdio' / 'connected')在 MCP
 * 消费目录中以裸字面量写出即构成第二真相源,应引用 MCP_TRANSPORT.* / MCP_SERVER_STATUS.* 等。
 *
 * 与 DOWNSTREAM 不同,词表值多为通用英文词('http'/'connected'),为避免全局误报,
 * 扫描范围限定在 MCP 消费目录(MCP_SCAN_DIRS)。
 */
const MCP_WORD_TABLES = [
  'MCP_TRANSPORT',
  'MCP_SERVER_STATUS',
  'MCP_GATEWAY_STATE',
  'MCP_PACKAGE_RUNNER',
  'MCP_CONTAINER_RUNNER',
  'MCP_MARKET_ERROR_CODE',
];

/** MCP 词表扫描范围(相对 webview/src;文件或目录);此范围外的同形字面量不受本守门约束。 */
const MCP_SCAN_PATHS = ['components/mcp', join('types', 'mcp.ts')];

function parseMcpWordTables(protocolSrc) {
  const tables = [];
  for (const constantName of MCP_WORD_TABLES) {
    const entries = parseGeneratedProtocolConstant(protocolSrc, constantName);
    const valueToKeys = new Map();
    for (const entry of entries) {
      if (!valueToKeys.has(entry.value)) valueToKeys.set(entry.value, []);
      valueToKeys.get(entry.value).push(entry.name);
    }
    tables.push({ constantName, valueToKeys, count: entries.length });
  }
  return tables;
}

function validateProtocolConsistency(protocolSrc) {
  const manifest = JSON.parse(readFileSync(MANIFEST_PATH, 'utf-8'));
  if (!Array.isArray(manifest.upstream) || !Array.isArray(manifest.downstream)) {
    throw new Error('protocol-manifest.json 缺少 upstream/downstream 数组');
  }

  const javaUpstream = parseEnumSource(readFileSync(UPSTREAM_JAVA_PATH, 'utf-8'), UPSTREAM_JAVA_PATH);
  const javaDownstream = parseEnumSource(
    readFileSync(DOWNSTREAM_JAVA_PATH, 'utf-8'),
    DOWNSTREAM_JAVA_PATH,
  );
  if (javaUpstream.length === 0 || javaDownstream.length === 0) {
    throw new Error('Java 协议枚举解析结果为空');
  }

  const generatedUpstream = parseGeneratedProtocolConstant(protocolSrc, 'UPSTREAM');
  const generatedDownstream = parseGeneratedProtocolConstant(protocolSrc, 'DOWNSTREAM');
  return [
    ...compareProtocolEntries(javaUpstream, manifest.upstream, 'Java UpstreamAction', 'manifest upstream'),
    ...compareProtocolEntries(
      javaDownstream,
      manifest.downstream,
      'Java DownstreamEvent',
      'manifest downstream',
    ),
    ...compareProtocolEntries(javaUpstream, generatedUpstream, 'Java UpstreamAction', 'generated UPSTREAM'),
    ...compareProtocolEntries(
      javaDownstream,
      generatedDownstream,
      'Java DownstreamEvent',
      'generated DOWNSTREAM',
    ),
  ];
}

// ── 2. 遍历目标文件 ────────────────────────────────────────────────────────

function collectTargetFiles(dir, acc = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch (e) {
    console.error(`[check-event-literals] ERROR: 无法读取目录 ${dir}: ${e.message}`);
    process.exit(2);
  }
  for (const name of entries) {
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      if (EXCLUDE_DIRS.has(name)) continue;
      collectTargetFiles(full, acc);
    } else if (st.isFile()) {
      if (!TARGET_EXTS.has(extname(name))) continue;
      if (EXCLUDE_FILE_PATTERNS.some((re) => re.test(name))) continue;
      acc.push(full);
    }
  }
  return acc;
}

// ── 3. 扫描字面量 ────────────────────────────────────────────────────────

/**
 * 匹配字符串字面量:单引号、双引号、无表达式插值的反引号。
 *
 * 故意不处理带 ${...} 插值的模板字符串(那种一定不是 DOWNSTREAM 协议常量)。
 * 字面量内容限制:不含同引号字符、不含换行、长度 ≤ 80(DOWNSTREAM value 都很短)。
 *
 * 4 个捕获组:整体 / 引号 / 内容 / 引号。返回 [{ quote, value, start }]。
 */
const LITERAL_RE = /(['"`])([^'"`\n\r]{1,80})\1/g;

/**
 * 把源码中的注释内容替换成等长空格(保留换行符以维持行号一致)。
 *
 * 必要性:JSDoc(@example)、行注释、块注释中常常出现协议字面量(如 'usage.update'),
 * 这些是文档说明而非真实漂移。如果不剥离注释,会大量误报。
 *
 * 实现要点(逐字符状态机):
 *   - 字符串字面量('...' / "..." / `...`)整段保留,避免字符串内的 // 或 /* 被误判为注释。
 *   - 行注释 //... 抠到行尾(不含换行符)。
 *   - 块注释 /* ... *\/ 抠到闭合,换行符保留以维持行号。
 *
 * 不处理嵌套模板表达式 `${...}`(DOWNSTREAM value 不会出现在插值表达式里,简化是有意为之)。
 */
function stripComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];

    // 单/双引号字符串:整段保留,避免字符串内的 // 被当注释
    if (c === "'" || c === '"') {
      const quote = c;
      let j = i + 1;
      while (j < n) {
        if (src[j] === '\\') { j += 2; continue; }
        if (src[j] === quote) { j++; break; }
        if (src[j] === '\n') break; // 字符串不跨行,异常保护
        j++;
      }
      out += src.slice(i, j);
      i = j;
      continue;
    }

    // 模板字符串:整段保留(简化:不识别嵌套 ${},够用)
    if (c === '`') {
      let j = i + 1;
      while (j < n) {
        if (src[j] === '\\') { j += 2; continue; }
        if (src[j] === '`') { j++; break; }
        j++;
      }
      out += src.slice(i, j);
      i = j;
      continue;
    }

    // 行注释 //... 到行尾(不含 \n)
    if (c === '/' && c2 === '/') {
      let j = i;
      while (j < n && src[j] !== '\n') j++;
      out += ' '.repeat(j - i);
      i = j;
      continue;
    }

    // 块注释 /* ... */:换行保留,其他字符换空格
    if (c === '/' && c2 === '*') {
      let j = i + 2;
      while (j < n && !(src[j] === '*' && src[j + 1] === '/')) j++;
      j = Math.min(n, j + 2);
      for (let k = i; k < j; k++) {
        out += src[k] === '\n' ? '\n' : ' ';
      }
      i = j;
      continue;
    }

    out += c;
    i++;
  }
  return out;
}

function scanFile(filePath, valueToKeys) {
  const rawSrc = readFileSync(filePath, 'utf-8');
  // 先剥离注释,确保只在「真实代码」中扫字面量。
  const src = stripComments(rawSrc);
  const hits = [];
  let m;
  LITERAL_RE.lastIndex = 0;
  while ((m = LITERAL_RE.exec(src)) !== null) {
    const value = m[2];
    const keys = valueToKeys.get(value);
    if (!keys || keys.length === 0) continue;
    // 必须是「整个字面量」等于 DOWNSTREAM value,而不是字面量的子串。
    // 上面 regex 已保证捕获组 2 是引号之间的完整内容,因此无需额外边界检查。
    const start = m.index;
    const line = computeLine(src, start);
    hits.push({ line, value, keys, quote: m[1] });
  }
  return hits;
}

function computeLine(src, index) {
  let line = 1;
  for (let i = 0; i < index; i++) {
    if (src.charCodeAt(i) === 10 /* \n */) line++;
  }
  return line;
}

// ── 4. allowlist & 报告 ───────────────────────────────────────────────────

function isAllowlisted(relPath, value) {
  return ALLOWLIST.some((a) => relPath.startsWith(a.file) && a.value === value);
}

function main() {
  try {
    if (!exists(SRC_DIR)) {
      throw new Error(`webview/src 不存在于 ${SRC_DIR}`);
    }

    const protocolSrc = readFileSync(PROTOCOL_PATH, 'utf-8');
    const protocolDrifts = validateProtocolConsistency(protocolSrc);
    if (protocolDrifts.length > 0) {
      console.error('[check-event-literals] ✗ Java enum / manifest / generated TypeScript 协议不一致:');
      for (const drift of protocolDrifts) {
        console.error(`  - ${drift}`);
      }
      process.exitCode = 1;
      return;
    }

    const { valueToKeys, count } = parseDownstream(protocolSrc);
    const files = collectTargetFiles(SRC_DIR);
    const drifts = [];

    for (const f of files) {
      const relPath = relative(ROOT, f).replace(/\\/g, '/');
      const hits = scanFile(f, valueToKeys);
      for (const h of hits) {
        if (isAllowlisted(relPath, h.value)) continue;
        drifts.push({ relPath, ...h, table: 'DOWNSTREAM' });
      }
    }

    // MCP 业务词表扫描:仅扫 MCP 消费路径(通用词如 'http' 全局扫描会误报)。
    const mcpTables = parseMcpWordTables(protocolSrc);
    const mcpPaths = MCP_SCAN_PATHS.map((d) => join(SRC_DIR, d));
    const mcpFiles = mcpPaths.flatMap((p) => {
      if (!exists(p)) return [];
      return statSync(p).isDirectory() ? collectTargetFiles(p) : [p];
    });
    let mcpWordCount = 0;
    for (const table of mcpTables) {
      mcpWordCount += table.count;
      for (const f of mcpFiles) {
        const relPath = relative(ROOT, f).replace(/\\/g, '/');
        const hits = scanFile(f, table.valueToKeys);
        for (const h of hits) {
          if (isAllowlisted(relPath, h.value)) continue;
          drifts.push({ relPath, ...h, table: table.constantName });
        }
      }
    }

    if (drifts.length === 0) {
      if (!isQuiet) {
        console.log(
          `[check-event-literals] ✓ 三方协议一致且无字面量漂移。扫描 ${files.length} 个文件,DOWNSTREAM 共 ${count} 条;MCP 词表扫描 ${mcpFiles.length} 个文件,共 ${mcpWordCount} 个词。`,
        );
      }
      return;
    }

    drifts.sort((a, b) =>
      a.relPath === b.relPath ? a.line - b.line : a.relPath < b.relPath ? -1 : 1,
    );

    console.error('[check-event-literals] ✗ 发现协议字面量漂移(应使用 SSOT 常量引用):');
    for (const d of drifts) {
      const prefix = d.table === 'DOWNSTREAM' ? 'DOWNSTREAM' : d.table;
      const suggestion = d.keys.length === 1
        ? `${prefix}.${d.keys[0]}`
        : d.keys.map((k) => `${prefix}.${k}`).join(' 或 ');
      console.error(
        `  ${d.relPath}:${d.line}  ${d.quote}${d.value}${d.quote}  →  ${suggestion}`,
      );
    }
    console.error('');
    console.error(`共 ${drifts.length} 处漂移。请替换为 SSOT 常量引用(DOWNSTREAM.* / MCP_*.*)以保持单一真相源。`);
    console.error('详见:AGENTS.md 总则三 / docs/comprehensive-optimization-directions.md §A8。');
    process.exitCode = 1;
  } catch (error) {
    console.error(`[check-event-literals] ERROR: ${error.message}`);
    process.exitCode = 2;
  }
}

function exists(p) {
  try {
    statSync(p);
    return true;
  } catch {
    return false;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
