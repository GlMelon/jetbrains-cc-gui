#!/usr/bin/env node
// 临时审计脚本:找出所有不带 executor 参数的 CompletableFuture *Async( 调用。
// 方法:定位 *Async( 后做括号平衡匹配到闭括号,统计实参列表顶层逗号数(0 个逗号 = 仅一个 lambda 实参 = 无 executor)。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.argv[2] ?? 'src/main';
const ASYNC_RE = /\.(supplyAsync|runAsync|thenApplyAsync|thenAcceptAsync|thenRunAsync|thenComposeAsync|thenCombineAsync|handleAsync|whenCompleteAsync|exceptionallyAsync)\(/g;

const results = [];

function scanFile(path) {
  const src = readFileSync(path, 'utf8');
  // 去块注释/行注释/字符串字面量,避免注释里的 Async( 或字符串里的括号干扰平衡
  const clean = src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => ' '.repeat(m.length))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length))
    .replace(/"(?:\\.|[^"\\])*"/g, (m) => ' '.repeat(m.length))
    .replace(/'(?:\\.|[^'\\])*'/g, (m) => ' '.repeat(m.length));
  let m;
  ASYNC_RE.lastIndex = 0;
  while ((m = ASYNC_RE.exec(clean)) !== null) {
    const openIdx = m.index + m[0].length - 1; // '(' 的位置
    let depth = 0, topCommas = 0;
    for (let i = openIdx; i < clean.length; i++) {
      const ch = clean[i];
      if (ch === '(') depth++;
      else if (ch === ')') { depth--; if (depth === 0) break; }
      else if (ch === ',' && depth === 1) topCommas++;
    }
    if (topCommas === 0) {
      const line = clean.slice(0, m.index).split('\n').length;
      results.push(`${path.replace(/\\/g, '/')}:${line}  .${m[1]}(...)  [无 executor]`);
    }
  }
}

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const s = statSync(p);
    if (s.isDirectory()) walk(p);
    else if (name.endsWith('.java')) scanFile(p);
  }
}

walk(ROOT);
console.log(`共 ${results.length} 处无 executor 的 Async 调用:\n`);
for (const r of results) console.log(r);
