#!/usr/bin/env node
/**
 * check-locale-coverage.mjs
 *
 * I18N1 前端 locale coverage baseline 守门(docs/comprehensive-optimization-directions.md §9 I18N1)。
 *
 * 背景:
 *   webview/src/i18n/locales/en.json 是前端文案键的基准 SSOT。其余 locale 应是 en 键集的
 *   子集(允许历史翻译缺口,允许少量额外键)。无守门时,新增 en 键若忘记同步翻译,会静默扩大
 *   缺口,非英语用户体验劣化无人察觉。
 *
 * 规则:
 *   - baseline(locale-coverage-baseline.json)记录每个 locale 当前允许的最大缺失键数(历史快照)。
 *   - CI fail 条件:任何 locale 实际缺失键数 > baseline(coverage 下降 = 新增 en 键未翻译)。
 *   - 多余键(locale 有 en 没有的键)仅报告不 fail(疑似拼写错/废弃键,人工判断)。
 *   - 主语言 en 不参与缺失比较(它是基准);但 en 键集增减会反映到其他 locale 的缺失数。
 *
 * 历史缺口分批清偿:开发者补齐某 locale 翻译后,缺失数下降,手动收紧 baseline.json 对应值
 * (把数字改成新的更小实际值),baseline 单调下降体现清偿进度。区分 key coverage(本脚本)与
 * 翻译质量(人工/机器评估,不在本脚本职责)。
 *
 * 用法:
 *   node webview/scripts/check-locale-coverage.mjs            # 守门(actual vs baseline)
 *   node webview/scripts/check-locale-coverage.mjs --init     # 首次/重建:用当前实际缺失数生成 baseline
 *   node webview/scripts/check-locale-coverage.mjs --verbose  # 打印缺失键/多余键列表
 *
 * 退出码:
 *   0 — 所有 locale 在 baseline 内
 *   1 — 有 locale coverage 退化 / baseline 缺失
 */
import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const LOCALES_DIR = join(__dirname, '..', 'src', 'i18n', 'locales');
const BASELINE_PATH = join(__dirname, 'locale-coverage-baseline.json');
const BASELINE_LOCALE = 'en';

// 递归 flatten 嵌套 JSON 为点号路径键集合(如 common.save / settings.models.title)。
function flattenKeys(obj, prefix, out) {
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      flattenKeys(v, path, out);
    } else {
      out.add(path);
    }
  }
  return out;
}

function loadLocaleKeys(locale) {
  const raw = readFileSync(join(LOCALES_DIR, `${locale}.json`), 'utf8');
  return flattenKeys(JSON.parse(raw), '', new Set());
}

function availableLocales() {
  return readdirSync(LOCALES_DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => f.slice(0, -5))
    .sort();
}

function computeCoverage() {
  const enKeys = loadLocaleKeys(BASELINE_LOCALE);
  const locales = availableLocales().filter((l) => l !== BASELINE_LOCALE);
  const report = {};
  for (const locale of locales) {
    const keys = loadLocaleKeys(locale);
    const missing = [...enKeys].filter((k) => !keys.has(k));
    const extra = [...keys].filter((k) => !enKeys.has(k));
    report[locale] = { missing: missing.length, extra: extra.length, missingKeys: missing, extraKeys: extra };
  }
  return { enKeyCount: enKeys.size, report };
}

const args = new Set(process.argv.slice(2));
const verbose = args.has('--verbose');
const init = args.has('--init');

const { enKeyCount, report } = computeCoverage();

if (init) {
  const baseline = {};
  for (const [locale, r] of Object.entries(report)) {
    baseline[locale] = r.missing;
  }
  writeFileSync(BASELINE_PATH, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8');
  console.log(`[i18n] baseline written to ${BASELINE_PATH} (en = ${enKeyCount} keys)`);
  console.log(JSON.stringify(baseline, null, 2));
  process.exit(0);
}

if (!existsSync(BASELINE_PATH)) {
  console.error(`[i18n] FAIL: baseline not found at ${BASELINE_PATH}.`);
  console.error('[i18n] Run `node webview/scripts/check-locale-coverage.mjs --init` to snapshot current coverage.');
  process.exit(1);
}

const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));

const lines = [];
lines.push(`[i18n] baseline locale en = ${enKeyCount} keys`);
lines.push('[i18n] locale coverage (missing/extra vs en; baseline = allowed max missing):');

let failed = false;
for (const [locale, r] of Object.entries(report)) {
  const allowed = baseline[locale];
  const noBaseline = typeof allowed !== 'number';
  const degraded = !noBaseline && r.missing > allowed;
  if (degraded || noBaseline) {
    failed = true;
  }
  const mark = degraded || noBaseline ? 'FAIL' : 'ok ';
  const allowedStr = noBaseline ? '(no baseline)' : `(<=${allowed})`;
  lines.push(`  ${mark} ${locale.padEnd(6)} missing=${r.missing} ${allowedStr}  extra=${r.extra}`);
  if (verbose) {
    if (r.missingKeys.length) {
      const sample = r.missingKeys.slice(0, 20).join(', ');
      const more = r.missingKeys.length > 20 ? ` ... (+${r.missingKeys.length - 20} more)` : '';
      lines.push(`         missing: ${sample}${more}`);
    }
    if (r.extraKeys.length) {
      lines.push(`         extra:   ${r.extraKeys.slice(0, 20).join(', ')}`);
    }
  }
}

console.log(lines.join('\n'));

if (failed) {
  console.error('[i18n] FAIL: locale coverage degraded below baseline (new keys added to en without translation), or a locale has no baseline entry.');
  console.error('[i18n] Fix: add the missing translations, OR consciously accept the debt by running --init to re-snapshot.');
  process.exit(1);
}

console.log('[i18n] OK: all locales within baseline.');
process.exit(0);
