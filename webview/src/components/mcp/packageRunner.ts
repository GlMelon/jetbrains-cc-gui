/**
 * MCP 命令 runner 与包名解析(B3/SEC-06 前端预拦截)。
 *
 * 与后端 McpCommandRiskEvaluator 的 KNOWN_RUNNERS 同源:runner 词表 SSOT 在 Java enum
 * (McpPackageRunner / McpContainerRunner),经 generate-protocol-types.mjs 生成到
 * protocol.ts,此后端只校验 runner 是否已知(放行),从不解析包名——这是 SEC-06 漏洞。
 * 本模块在前端为包管理型 / 容器型 runner 提取"将安装/运行的包(或镜像)名",供二次确认弹窗展示。
 */
import { MCP_CONTAINER_RUNNER, MCP_PACKAGE_RUNNER } from '../../generated/protocol';
import type { McpServerSpec } from '../../types/mcp';

/** 包管理型 runner:会从网络拉取并执行任意包(与后端 McpCommandRiskEvaluator 同源) */
const PACKAGE_RUNNERS: string[] = Object.values(MCP_PACKAGE_RUNNER);
/** 容器型 runner:会拉取并运行任意镜像(与后端 McpCommandRiskEvaluator 同源) */
const CONTAINER_RUNNERS: string[] = Object.values(MCP_CONTAINER_RUNNER);

export interface PackageRunnerInfo {
  runner: string;
  /** 将安装/运行的包名(包管理型)或镜像名(容器型);解析不到时为 null */
  packageName: string | null;
  /** 完整命令预览(command + args),供用户核对 */
  fullCommand: string;
  kind: 'package' | 'container';
}

/** 取 command 的首 token 并归一化:剥离路径前缀与 Windows 可执行后缀 */
function firstToken(command: string | undefined): string | null {
  if (!command || typeof command !== 'string') return null;
  const head = command.trim().split(/\s+/)[0];
  if (!head) return null;
  // 剥离路径前缀:/usr/local/bin/npx → npx;C:\...\npx.cmd → npx
  const base = head.replace(/^.*[\\/]/, '');
  return base.replace(/\.(exe|cmd|bat)$/i, '').toLowerCase();
}

/** 取 args 中首个非 flag token(跳过 -y / --yes / -p 等) */
function firstNonFlagArg(args: string[] | undefined, startIdx = 0): string | null {
  if (!Array.isArray(args) || args.length === 0) return null;
  for (let i = startIdx; i < args.length; i++) {
    const a = args[i];
    if (typeof a !== 'string') continue;
    if (a.startsWith('-')) continue;
    return a;
  }
  return null;
}

/**
 * 解析 server spec 的 runner 与包名。返回 null 表示非包管理/容器型 runner
 * (如 node/python 直跑本地脚本、http/sse 远程服务),无需二次确认。
 */
export function parsePackageRunner(spec: McpServerSpec | undefined): PackageRunnerInfo | null {
  if (!spec) return null;
  const runner = firstToken(spec.command);
  if (!runner) return null;
  const fullCommand = [spec.command || '', ...(Array.isArray(spec.args) ? spec.args : [])]
    .filter((s): s is string => typeof s === 'string' && s.length > 0)
    .join(' ')
    .trim();

  if (PACKAGE_RUNNERS.includes(runner)) {
    return { runner, packageName: firstNonFlagArg(spec.args), fullCommand, kind: 'package' };
  }
  if (CONTAINER_RUNNERS.includes(runner)) {
    // docker/podman:镜像通常在 run 子命令后,否则取首个非 flag
    const args = Array.isArray(spec.args) ? spec.args : [];
    const runIdx = args.findIndex((a) => a === 'run');
    const image = firstNonFlagArg(args, runIdx !== -1 ? runIdx + 1 : 0);
    return { runner, packageName: image, fullCommand, kind: 'container' };
  }
  return null;
}
