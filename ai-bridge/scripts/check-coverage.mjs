#!/usr/bin/env node
/**
 * check-coverage.mjs — ai-bridge c8 覆盖率防倒退 gate(§11.5 / §T1)。
 * 对称 webview/scripts/check-locale-coverage.mjs 的 --init / 守门 / --verbose 模式。
 *
 * 与 locale gate 方向相反:coverage baseline = 最小覆盖率(越大越好),
 * actual < baseline = FAIL(locale gate 是允许最大缺失,actual > baseline = FAIL)。
 *
 * 第一阶段:total branches + lines 双指标入 gate,all:false(只计被测试加载的文件)。
 * 用 istanbul-lib-coverage 的 getCoverageSummary() 算 total,复用 c8 同款库,
 * 保证数字与 c8 text-summary 完全一致(coverage-final.json 是原始计数,手算易错)。
 *
 * 用法:
 *   node ai-bridge/scripts/check-coverage.mjs             # 守门(actual >= baseline)
 *   node ai-bridge/scripts/check-coverage.mjs --init      # 用当前 actual 生成/重写 baseline
 *   node ai-bridge/scripts/check-coverage.mjs --verbose   # 打印每指标 actual/baseline/delta
 *
 * 退出码:0 — 在 baseline 内;1 — 覆盖率退化 / baseline 缺失 / 报告缺失
 */
import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
// istanbul-lib-coverage 是 CommonJS,ESM 下用 default import + 解构(Node CJS interop)
import istanbulLibCoverage from 'istanbul-lib-coverage';

const { createCoverageMap } = istanbulLibCoverage;
const __dirname = dirname(fileURLToPath(import.meta.url));
const REPORT = join(__dirname, '..', 'coverage', 'coverage-final.json');
const BASELINE = join(__dirname, 'coverage-baseline.json');

function readActual() {
  if (!existsSync(REPORT)) {
    console.error(`[cov] FAIL: coverage report not found at ${REPORT}`);
    console.error('[cov] Run `node ai-bridge/scripts/run-coverage.mjs` first to generate it.');
    process.exit(1);
  }
  const raw = JSON.parse(readFileSync(REPORT, 'utf8'));
  const s = createCoverageMap(raw).getCoverageSummary();
  return {
    branches: s.branches.pct,
    lines: s.lines.pct,
    statements: s.statements.pct,
    functions: s.functions.pct,
  };
}

const args = new Set(process.argv.slice(2));
const verbose = args.has('--verbose');
const init = args.has('--init');
const actual = readActual();

if (init) {
  const baseline = {
    branches: actual.branches,
    lines: actual.lines,
    statements: actual.statements,
    functions: actual.functions,
    snapshotAt: new Date().toISOString(),
    note: 'ai-bridge c8 total pct (all:false, imported files only). gate: actual<branches|lines> < baseline = FAIL.',
    tool: 'c8@10.1.3',
    config: 'ai-bridge/.c8rc.json',
  };
  writeFileSync(BASELINE, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8');
  console.log(`[cov] baseline written to ${BASELINE}`);
  console.log(`[cov] branches=${baseline.branches}% lines=${baseline.lines}% statements=${baseline.statements}% functions=${baseline.functions}%`);
  process.exit(0);
}

if (!existsSync(BASELINE)) {
  console.error(`[cov] FAIL: baseline not found at ${BASELINE}.`);
  console.error('[cov] Run `node ai-bridge/scripts/check-coverage.mjs --init` to snapshot current coverage.');
  process.exit(1);
}

const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));

const checks = [
  { name: 'branches', actual: actual.branches, baseline: baseline.branches },
  { name: 'lines', actual: actual.lines, baseline: baseline.lines },
];

const out = ['[cov] ai-bridge coverage (c8 total, all:false; baseline = min allowed pct):'];
let failed = false;
for (const c of checks) {
  const noBaseline = typeof c.baseline !== 'number';
  const degraded = !noBaseline && c.actual < c.baseline;
  if (degraded || noBaseline) failed = true;
  const mark = degraded || noBaseline ? 'FAIL' : 'ok ';
  const baseStr = noBaseline ? '(no baseline)' : `(>=${c.baseline})`;
  const delta = noBaseline ? '' : ` delta ${(c.actual - c.baseline).toFixed(2)}`;
  out.push(`  ${mark} ${c.name.padEnd(9)} actual=${c.actual}% ${baseStr}${delta}`);
}
out.push(`       statements=${actual.statements}%  functions=${actual.functions}% (display only)`);

if (verbose) {
  out.push(`[cov] baseline snapshot: ${baseline.snapshotAt || '(unknown)'}`);
}

console.log(out.join('\n'));

if (failed) {
  console.error('[cov] FAIL: coverage degraded below baseline, or a metric has no baseline entry.');
  console.error('[cov] Fix: add tests to recover coverage, OR consciously accept by running --init after intentional changes.');
  process.exit(1);
}

console.log('[cov] OK: coverage within baseline.');
process.exit(0);
