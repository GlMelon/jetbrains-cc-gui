#!/usr/bin/env node
// @file I18N baseline 键差分检测脚本(I18N1 前端 + I18N2 后端)
// @see docs/comprehensive-optimization-directions.md 第 9 节 I18N1 / I18N2
//
// 用法(从仓库根或 scripts/ 任一位置均可):
//   node scripts/check-i18n-keys.mjs                  # 检测模式:与 baseline 对比,coverage 降低则非零退出
//   node scripts/check-i18n-keys.mjs --update-baseline # 刷新模式:重写 scripts/i18n-baseline.json(主语言不完整仍失败)
//   node scripts/check-i18n-keys.mjs --quiet           # 静默:仅在失败时输出
//   node scripts/check-i18n-keys.mjs --help
//
// 设计:
//   - 前端 SSOT = webview/src/i18n/locales/en.json(嵌套 JSON,递归扁平化为点号 key)
//   - 后端 SSOT = src/main/resources/messages/ClaudeCodeGuiBundle.properties(base bundle)
//   - 主语言完整性硬失败:前端 en 自身、后端 zh 相对 base(文档 I18N2 注明 zh 完整翻译)
//   - coverage baseline:某 locale 缺失键数 > baseline → 非零退出
//   - 纯 Node ESM,零外部依赖,CI 可直接 `node scripts/check-i18n-keys.mjs` 调用
//   - 不机器翻译填充,只做差分检测(文档 I18N1 明确禁止"以机器填充冒充完成")

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { argv, exit, stdout, stderr } from 'node:process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, '..');

// ----------------------------- 配置 -----------------------------

const FRONTEND_LOCALE_DIR = join(REPO_ROOT, 'webview', 'src', 'i18n', 'locales');
const BACKEND_BUNDLE_DIR = join(REPO_ROOT, 'src', 'main', 'resources', 'messages');

// 前端 locale 清单(排除 SSOT 自身)
const FRONTEND_LOCALES = ['zh', 'zh-TW', 'ko', 'ja', 'es', 'fr', 'hi', 'ru', 'pt-BR'];
const FRONTEND_SSOT = 'en';

// 后端 bundle 后缀清单(排除 base 自身)
const BACKEND_LOCALES = ['en', 'zh', 'zh_TW', 'ja', 'es', 'fr', 'hi', 'ru'];
const BACKEND_BASE_FILE = 'ClaudeCodeGuiBundle.properties';

// 主语言:缺失即硬失败(忽略 baseline)
//   前端 en = SSOT 自身(此处校验 en 解析无异常且键集合非空)
//   后端 zh = 文档 I18N2 注明的完整翻译主语言
const FRONTEND_PRIMARY = 'en';
const BACKEND_PRIMARY = 'zh';

const BASELINE_PATH = join(__dirname, 'i18n-baseline.json');
const MISSING_KEY_SAMPLE = 8; // 报告里每个 locale 最多展示的缺失键示例数

// ----------------------------- 工具 -----------------------------

function parseArgs(args) {
  const flags = {
    updateBaseline: false,
    quiet: false,
    help: false,
    unknown: [],
  };
  for (const a of args.slice(2)) {
    if (a === '--update-baseline' || a === '--init-baseline') flags.updateBaseline = true;
    else if (a === '--quiet' || a === '-q') flags.quiet = true;
    else if (a === '--help' || a === '-h') flags.help = true;
    else flags.unknown.push(a);
  }
  return flags;
}

function usage() {
  return [
    'I18N baseline 键差分检测(前端 I18N1 + 后端 I18N2)',
    '',
    '用法:',
    '  node scripts/check-i18n-keys.mjs                  检测:与 baseline 对比,coverage 下降则非零退出',
    '  node scripts/check-i18n-keys.mjs --update-baseline 刷新 baseline(主语言不完整仍失败)',
    '  node scripts/check-i18n-keys.mjs --quiet           静默模式',
    '  node scripts/check-i18n-keys.mjs --help            显示帮助',
  ].join('\n');
}

/** 递归扁平化嵌套 JSON 为点号 key 集合 */
function flattenJson(obj, prefix, out) {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    for (const k of Object.keys(obj)) {
      const next = prefix ? `${prefix}.${k}` : k;
      flattenJson(obj[k], next, out);
    }
  } else {
    // 叶子节点(null / string / number / array / boolean)登记为完整 key
    out.add(prefix);
  }
  return out;
}

function loadJsonLocale(file) {
  const raw = readFileSync(file, 'utf8');
  let json;
  try {
    json = JSON.parse(raw);
  } catch (err) {
    throw new Error(`JSON 解析失败 ${file}: ${err.message}`);
  }
  if (!json || typeof json !== 'object' || Array.isArray(json)) {
    throw new Error(`locale 根必须是对象 ${file}`);
  }
  const keys = new Set();
  flattenJson(json, '', keys);
  return keys;
}

/**
 * 解析 .properties 文件,返回 key 集合。
 * Properties 规范参考 java.util.Properties:
 *   - '# ' 或 '! ' 开头为注释
 *   - 空行跳过
 *   - key/value 以首个未转义的 '=' 或 ':' 分隔(也可用空白分隔)
 *   - 行尾 '\' 表示续行
 *   - key 中的反斜杠转义在此场景下不存在(key 全部 ASCII 标识符)
 * 此处实现:仅取 key,支持续行合并;Unicode 转义不影响 key 提取。
 */
function loadPropertiesKeys(file) {
  const raw = readFileSync(file, 'utf8');
  // 续行合并:把以 '\' 结尾的行与下一行拼起来
  const logicalLines = [];
  let acc = null;
  for (const line of raw.split(/\r\n|\n|\r/)) {
    if (acc !== null) {
      // 上一行是续行,去除行首空白后拼接
      acc += line.replace(/^[ \t]+/, '');
    } else {
      acc = line;
    }
    // 判断当前 acc 是否仍以奇数个反斜杠结尾(续行)
    const trailing = acc.match(/\\+$/);
    const isContinued = trailing && trailing[0].length % 2 === 1;
    if (isContinued) {
      acc = acc.slice(0, -1); // 去掉末尾反斜杠
    } else {
      logicalLines.push(acc);
      acc = null;
    }
  }
  if (acc !== null) logicalLines.push(acc);

  const keys = new Set();
  for (let line of logicalLines) {
    // 去除首尾空白后判断注释/空行
    const trimmed = line.trim();
    if (trimmed === '') continue;
    if (trimmed.startsWith('#') || trimmed.startsWith('!')) continue;

    // 提取 key:首个未转义的 '=' / ':' / 行内空白
    // 本仓库 key 全部为 '[a-zA-Z0-9_.-]+' 后跟 '=',直接走快速路径
    let sepIdx = -1;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (ch === '\\') {
        i++; // 跳过被转义的字符
        continue;
      }
      if (ch === '=' || ch === ':') {
        sepIdx = i;
        break;
      }
      if (ch === ' ' || ch === '\t') {
        // 空白分隔符(允许 key 后跟空白再跟 value)
        sepIdx = i;
        break;
      }
    }
    const key = (sepIdx === -1 ? line : line.slice(0, sepIdx)).trim();
    if (key === '') continue;
    keys.add(key);
  }
  return keys;
}

/** 计算 candidate 相对 ssot 的缺失键 */
function diffKeys(ssot, candidate) {
  const missing = [];
  for (const k of ssot) {
    if (!candidate.has(k)) missing.push(k);
  }
  missing.sort();
  return missing;
}

// ----------------------------- 检测核心 -----------------------------

function detectFrontend() {
  const ssotFile = join(FRONTEND_LOCALE_DIR, `${FRONTEND_SSOT}.json`);
  const ssotKeys = loadJsonLocale(ssotFile);
  const result = {
    ssotLocale: FRONTEND_SSOT,
    ssotKeyCount: ssotKeys.size,
    ssotFile,
    locales: {},
  };
  for (const loc of FRONTEND_LOCALES) {
    const file = join(FRONTEND_LOCALE_DIR, `${loc}.json`);
    if (!existsSync(file)) {
      result.locales[loc] = { missing: null, missingCount: -1, sample: [], file, missingFile: true };
      continue;
    }
    const keys = loadJsonLocale(file);
    const missing = diffKeys(ssotKeys, keys);
    result.locales[loc] = {
      missing,
      missingCount: missing.length,
      sample: missing.slice(0, MISSING_KEY_SAMPLE),
      file,
    };
  }
  return result;
}

function detectBackend() {
  const ssotFile = join(BACKEND_BUNDLE_DIR, BACKEND_BASE_FILE);
  const ssotKeys = loadPropertiesKeys(ssotFile);
  const result = {
    ssotLocale: 'base',
    ssotKeyCount: ssotKeys.size,
    ssotFile,
    locales: {},
  };
  for (const loc of BACKEND_LOCALES) {
    const file = join(BACKEND_BUNDLE_DIR, `ClaudeCodeGuiBundle_${loc}.properties`);
    if (!existsSync(file)) {
      result.locales[loc] = { missing: null, missingCount: -1, sample: [], file, missingFile: true };
      continue;
    }
    const keys = loadPropertiesKeys(file);
    const missing = diffKeys(ssotKeys, keys);
    result.locales[loc] = {
      missing,
      missingCount: missing.length,
      sample: missing.slice(0, MISSING_KEY_SAMPLE),
      file,
    };
  }
  return result;
}

// ----------------------------- 报告 -----------------------------

function formatReport(frontend, backend) {
  const lines = [];
  lines.push('=== I18N 键差分检测报告 ===');
  lines.push('');
  lines.push(`[前端 I18N1] SSOT=${frontend.ssotLocale} (${frontend.ssotKeyCount} keys)  ${frontend.ssotFile}`);
  for (const loc of FRONTEND_LOCALES) {
    const info = frontend.locales[loc];
    const tag = info.missingFile ? '文件缺失' : `${info.missingCount} missing`;
    lines.push(`  - ${loc.padEnd(8)} ${tag}`);
    if (!info.missingFile && info.sample.length > 0) {
      lines.push(`      示例: ${info.sample.join(', ')}`);
    }
  }
  lines.push('');
  lines.push(`[后端 I18N2] SSOT=base (${backend.ssotKeyCount} keys)  ${backend.ssotFile}`);
  for (const loc of BACKEND_LOCALES) {
    const info = backend.locales[loc];
    const tag = info.missingFile ? '文件缺失' : `${info.missingCount} missing`;
    lines.push(`  - ${loc.padEnd(8)} ${tag}`);
    if (!info.missingFile && info.sample.length > 0) {
      lines.push(`      示例: ${info.sample.join(', ')}`);
    }
  }
  return lines.join('\n');
}

/** 构造 baseline 数据(仅记录每个 locale 的 missingCount) */
function buildBaselineData(frontend, backend) {
  const fe = {};
  for (const loc of FRONTEND_LOCALES) {
    fe[loc] = frontend.locales[loc].missingCount;
  }
  const be = {};
  for (const loc of BACKEND_LOCALES) {
    be[loc] = backend.locales[loc].missingCount;
  }
  return {
    _meta: {
      generatedAt: new Date().toISOString(),
      frontendSsotKeyCount: frontend.ssotKeyCount,
      backendSsotKeyCount: backend.ssotKeyCount,
      note: 'baseline 冻结各 locale 相对 SSOT 的缺失键数;后续运行缺失数高于 baseline 即失败',
    },
    frontend: { ssot: FRONTEND_SSOT, missingCounts: fe },
    backend: { ssot: 'base', missingCounts: be },
  };
}

// ----------------------------- 主流程 -----------------------------

function main() {
  const flags = parseArgs(argv);
  if (flags.help) {
    stdout.write(usage() + '\n');
    exit(0);
  }
  if (flags.unknown.length > 0) {
    stderr.write(`未知参数: ${flags.unknown.join(', ')}\n`);
    stderr.write(usage() + '\n');
    exit(2);
  }

  const frontend = detectFrontend();
  const backend = detectBackend();

  if (!flags.quiet) {
    stdout.write(formatReport(frontend, backend) + '\n\n');
  }

  // ---- 主语言完整性硬失败(忽略 baseline)----
  const failures = [];

  // 前端主语言 en:SSOT 自身,检测是否有"叶子解析异常"。JSON 不允许重复键,
  // 故此处以"en 文件加载失败抛异常"为失败条件(已在 loadJsonLocale 抛出)。
  // 为保持显式校验入口,这里登记 en 的键数为 0 视为失败。
  if (frontend.ssotKeyCount === 0) {
    failures.push(`[前端] 主语言 ${FRONTEND_PRIMARY}(SSOT)解析得到 0 个键,判定文件损坏`);
  }
  const bePrimary = backend.locales[BACKEND_PRIMARY];
  if (bePrimary && !bePrimary.missingFile && bePrimary.missingCount > 0) {
    failures.push(
      `[后端] 主语言 ${BACKEND_PRIMARY} 相对 base 缺失 ${bePrimary.missingCount} 个键(文档 I18N2 要求 zh 完整): ` +
        `${bePrimary.sample.join(', ')}`
    );
  }
  if (bePrimary && bePrimary.missingFile) {
    failures.push(`[后端] 主语言 ${BACKEND_PRIMARY} 文件缺失: ${bePrimary.file}`);
  }

  // ---- baseline 对比 ----
  let baseline = null;
  if (existsSync(BASELINE_PATH)) {
    try {
      baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));
    } catch (err) {
      failures.push(`baseline 解析失败 ${BASELINE_PATH}: ${err.message}`);
    }
  }

  if (flags.updateBaseline) {
    const data = buildBaselineData(frontend, backend);
    writeFileSync(BASELINE_PATH, JSON.stringify(data, null, 2) + '\n', 'utf8');
    if (!flags.quiet) {
      stdout.write(`baseline 已刷新: ${BASELINE_PATH}\n`);
      stdout.write('请 review 后 git add 提交入库。\n');
    }
    // 刷新模式仍受主语言完整性约束;baseline 对比跳过
    if (failures.length > 0) {
      stderr.write('\n=== 失败(主语言完整性,刷新模式同样拦截)===\n');
      for (const f of failures) stderr.write(`  - ${f}\n`);
      exit(1);
    }
    exit(0);
  }

  // 检测模式:与 baseline 对比
  if (!baseline) {
    // 首次运行:无 baseline,自动生成并提示主线程确认
    const data = buildBaselineData(frontend, backend);
    writeFileSync(BASELINE_PATH, JSON.stringify(data, null, 2) + '\n', 'utf8');
    if (!flags.quiet) {
      stdout.write(`首次运行:已生成 baseline ${BASELINE_PATH}\n`);
      stdout.write('请主线程确认后 git add 提交入库;后续运行将以本 baseline 为门槛。\n');
    }
    // 首次生成 baseline 时,主语言完整性失败仍需暴露
    if (failures.length > 0) {
      stderr.write('\n=== 失败(主语言完整性)===\n');
      for (const f of failures) stderr.write(`  - ${f}\n`);
      exit(1);
    }
    exit(0);
  }

  // 对比 baseline:某 locale missingCount > baseline → 失败(coverage 下降)
  const feBase = baseline.frontend?.missingCounts || {};
  const beBase = baseline.backend?.missingCounts || {};
  const regressions = [];
  for (const loc of FRONTEND_LOCALES) {
    const prev = feBase[loc];
    const curr = frontend.locales[loc].missingCount;
    if (typeof prev === 'number' && curr > prev) {
      regressions.push(`[前端] ${loc}: ${curr} > baseline ${prev} (coverage 下降)`);
    }
  }
  for (const loc of BACKEND_LOCALES) {
    const prev = beBase[loc];
    const curr = backend.locales[loc].missingCount;
    if (typeof prev === 'number' && curr > prev) {
      regressions.push(`[后端] ${loc}: ${curr} > baseline ${prev} (coverage 下降)`);
    }
  }

  // SSOT key 数下降也视为回归(可能是 baseline 之后 SSOT 被删键,导致 coverage 虚假变好)
  const fePrevSsotCount = baseline.frontend?.ssotKeyCount ?? frontend.ssotKeyCount;
  const bePrevSsotCount = baseline._meta?.backendSsotKeyCount ?? backend.ssotKeyCount;
  if (frontend.ssotKeyCount < fePrevSsotCount) {
    regressions.push(
      `[前端] SSOT(en)键数 ${frontend.ssotKeyCount} < baseline ${fePrevSsotCount}(SSOT 键被删除?)`
    );
  }
  if (backend.ssotKeyCount < bePrevSsotCount) {
    regressions.push(
      `[后端] SSOT(base)键数 ${backend.ssotKeyCount} < baseline ${bePrevSsotCount}(SSOT 键被删除?)`
    );
  }

  const allFailures = [...failures, ...regressions];
  if (allFailures.length > 0) {
    stderr.write('\n=== 检测失败 ===\n');
    for (const f of allFailures) stderr.write(`  - ${f}\n`);
    exit(1);
  }

  if (!flags.quiet) {
    stdout.write('检测通过:主语言完整,coverage 未低于 baseline。\n');
  }
  exit(0);
}

try {
  main();
} catch (err) {
  stderr.write(`致命错误: ${err && err.stack ? err.stack : err}\n`);
  exit(1);
}
