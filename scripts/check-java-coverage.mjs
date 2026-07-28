#!/usr/bin/env node
/**
 * check-java-coverage.mjs — Java JaCoCo 覆盖率防倒退 gate(docs §11.5 / §T1 第三阶段)。
 * 对称 ai-bridge/scripts/check-coverage.mjs 与 webview/scripts/check-coverage.mjs。
 *
 * 方向:coverage baseline = 最小覆盖率(越大越好),
 * actual<branches|lines> < baseline = FAIL(与 locale gate actual > baseline = FAIL 相反)。
 *
 * JaCoCo 字节码插桩确定性(无 V8 抖动)→ baseline 精确冻结(不取整)。
 * readActual 零依赖解析 jacocoTestReport.xml 的 report 级 <counter>
 * (紧跟最后一个 </package> 之后、</report> 之前的 6 个全局 total)。
 *
 * 用法:
 *   node scripts/check-java-coverage.mjs             # 守门(actual >= baseline)
 *   node scripts/check-java-coverage.mjs --init      # 用当前 actual 生成/重写 baseline
 *   node scripts/check-java-coverage.mjs --verbose   # 打印每指标 actual/baseline/delta
 *
 * 退出码:0 — 在 baseline 内;1 — 覆盖率退化 / baseline 缺失 / 报告缺失
 */
import { readFileSync, existsSync, writeFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPORT   = join(__dirname, '..', 'build', 'reports', 'jacoco', 'test', 'jacocoTestReport.xml');
const EXEC     = join(__dirname, '..', 'build', 'jacoco', 'test.exec');
const BASELINE = join(__dirname, 'java-coverage-baseline.json');

// report 级 counter 提取:切片到最后一个 </package>,在其后的尾段里扫 6 个 <counter>。
// 不能对全 XML 扫 <counter>——会重复累加 package/class/method/sourcefile 级 counter。
// JaCoCo report 总以:最后一个 </package> + 6 个 report 级 <counter/> + </report> 收尾,
// 故 lastIndexOf('</package>') 之后那一段恰好只含 report 级 counter,稳健且零依赖。
function readActual() {
  if (!existsSync(REPORT)) {
    console.error(`[cov] FAIL: JaCoCo XML report not found at ${REPORT}`);
    console.error('[cov] Run `./gradlew test jacocoTestReport` first to generate it.');
    process.exit(1);
  }
  const xml = readFileSync(REPORT, 'utf8');
  const lastPkgClose = xml.lastIndexOf('</package>');
  if (lastPkgClose === -1) {
    console.error('[cov] FAIL: malformed JaCoCo XML (no </package> found).');
    process.exit(1);
  }
  const tail = xml.slice(lastPkgClose); // </package> + report 级 counters + </report>
  const totals = {};
  // JaCoCo 属性顺序固定:type, missed, covered(自闭合 <counter .../>)。
  const re = /<counter\s+type="([^"]+)"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g;
  let m;
  while ((m = re.exec(tail)) !== null) {
    totals[m[1]] = { missed: Number(m[2]), covered: Number(m[3]) };
  }
  const pct = t => (t && (t.missed + t.covered) > 0)
    ? (t.covered / (t.missed + t.covered)) * 100
    : null;
  return {
    branches:   pct(totals.BRANCH),
    lines:      pct(totals.LINE),
    statements: pct(totals.INSTRUCTION), // JaCoCo 无 STATEMENT,INSTRUCTION 是最接近的指令级指标
    functions:  pct(totals.METHOD),
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
    note: 'Java JaCoCo total pct(classDirectories 收紧到 build/classes/java/main,排除 i18n/ui/startup/ProtocolManifestGenerator/JsUtils 胶水)。JaCoCo 字节码插桩确定性 → 精确冻结(不取整)。gate: actual<branches|lines> < baseline = FAIL。',
    tool: 'jacoco@0.8.12',
    config: 'build.gradle (jacocoTestReport.classDirectories excludes)',
  };
  writeFileSync(BASELINE, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8');
  console.log(`[cov] baseline written to ${BASELINE}`);
  console.log(`[cov] branches=${baseline.branches}% lines=${baseline.lines}% statements=${baseline.statements}% functions=${baseline.functions}%`);
  process.exit(0);
}

if (!existsSync(BASELINE)) {
  console.error(`[cov] FAIL: baseline not found at ${BASELINE}.`);
  console.error('[cov] Run `node scripts/check-java-coverage.mjs --init` to snapshot current coverage.');
  process.exit(1);
}

// 可选 stale 提示(软警告,不 fail):exec 比 XML 新 = test 跑过但报告没重生成。
// CI fresh checkout 天然规避;本地 dev 忘记重跑 jacocoTestReport 时给提示。
if (existsSync(EXEC) && existsSync(REPORT) && statSync(REPORT).mtimeMs < statSync(EXEC).mtimeMs) {
  console.error('[cov] WARN: test.exec is newer than jacocoTestReport.xml — XML may be stale.');
  console.error('[cov]        Re-run `./gradlew jacocoTestReport` (or `gradlew clean test jacocoTestReport`) before trusting these numbers.');
}

const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));

const checks = [
  { name: 'branches', actual: actual.branches, baseline: baseline.branches },
  { name: 'lines',    actual: actual.lines,    baseline: baseline.lines },
];

const out = ['[cov] java coverage (JaCoCo total, classDirectories filtered; baseline = min allowed pct):'];
let failed = false;
for (const c of checks) {
  const noBaseline = typeof c.baseline !== 'number' || typeof c.actual !== 'number';
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
