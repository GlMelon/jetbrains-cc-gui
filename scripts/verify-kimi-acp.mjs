#!/usr/bin/env node
/**
 * kimi ACP 通道端到端协议验证脚本(④ 真机验收自动化)。
 *
 * 用途:固化 kimi `kimi acp` 子命令的协议契约(thinking/tool_call/续接/标题/未登录),
 * 作为 Java 侧 KimiAcpCliSession 实现所基于的协议回归。kimi 升级后跑本脚本,
 * 若断言失败说明协议契约变化,需同步调整 KimiAcpStreamParser/KimiAcpProtocol。
 *
 * 不依赖 JVM/IntelliJ——纯 node spawn 真实 kimi acp,任何人 clone repo 后有 kimi 即可跑。
 * 需 kimi 已登录(未登录则 -32000,仅验证该路径)。
 *
 * 运行:node scripts/verify-kimi-acp.mjs
 * 退出码:0=全过,1=有失败。
 */
import { spawn } from 'child_process';
import { setTimeout as sleep } from 'timers/promises';

const CWD = 'D:/project/jetbrains-melon-cc-gui';
const HANDSHAKE_TIMEOUT = 30_000;
const TURN_TIMEOUT = 90_000;

let pass = 0, fail = 0;
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name} ${detail ? '— ' + detail : ''}`); }
}

class KimiAcpClient {
  constructor() {
    this.proc = spawn('kimi', ['acp'], { shell: true });
    this.nextId = 1;
    this.pending = new Map();
    this.updates = [];
    this.allSeen = [];  // 累计全部 update(不被 step 清空),供跨 step 断言
    this.buf = '';
    this.stderrBuf = '';
    this.proc.stdout.setEncoding('utf8');
    this.proc.stdout.on('data', (d) => {
      this.buf += d;
      let i;
      while ((i = this.buf.indexOf('\n')) >= 0) {
        const line = this.buf.slice(0, i);
        this.buf = this.buf.slice(i + 1);
        if (line.trim()) this.route(line);
      }
    });
    this.proc.stderr.on('data', (d) => { this.stderrBuf += d.toString(); });
  }
  route(line) {
    let m; try { m = JSON.parse(line); } catch { return; }
    if (m.id != null && (m.result != null || m.error != null)) {
      const f = this.pending.get(m.id);
      if (f) { f(m); this.pending.delete(m.id); }
      return;
    }
    if (m.method === 'session/update') { this.updates.push(m.params.update); this.allSeen.push(m.params.update); }
  }
  request(method, params, timeoutMs) {
    const id = this.nextId++;
    const p = new Promise((resolve, reject) => {
      this.pending.set(id, (m) => m.error ? reject(m.error) : resolve(m.result));
      setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, timeoutMs);
    });
    this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    return p;
  }
  respondToServer(id, result) {
    this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
  }
  counts() {
    const c = {};
    for (const u of this.updates) c[u.sessionUpdate] = (c[u.sessionUpdate] || 0) + 1;
    return c;
  }
  close() { try { this.proc.stdin.end(); } catch {} this.proc.kill(); }
}

async function main() {
  console.log('kimi ACP 端到端协议验证\n(需 kimi 已登录;未登录仅验证 -32000 路径)\n');

  const client = new KimiAcpClient();
  try {
    // ── 1. initialize 握手 ──
    console.log('[1/6] initialize 握手');
    let init;
    try {
      init = await client.request('initialize', {
        protocolVersion: 1,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      }, HANDSHAKE_TIMEOUT);
      check('initialize 返回 protocolVersion', init?.protocolVersion === 1);
      check('agentCapabilities.loadSession 存在', init?.agentCapabilities?.loadSession === true);
      check('promptCapabilities.image=true(图片走 ACP blocks)', init?.agentCapabilities?.promptCapabilities?.image === true);
      check('agentInfo.name 含 Kimi', JSON.stringify(init?.agentInfo).includes('Kimi'));
    } catch (e) {
      check('initialize 握手', false, e.message);
      console.log('\n initialize 失败,后续跳过(可能 kimi 未安装)');
      client.close();
      return report();
    }

    // ── 2. session/new + configOptions(含 thinking) ──
    console.log('[2/6] session/new + configOptions');
    let session;
    try {
      session = await client.request('session/new', { cwd: CWD, mcpServers: [] }, HANDSHAKE_TIMEOUT);
    } catch (e) {
      if (e?.code === -32000) {
        check('未登录返回 -32000', true);
        console.log('  (kimi 未登录,仅验证 -32000 路径,后续跳过)');
        client.close();
        return report();
      }
      check('session/new', false, JSON.stringify(e));
      client.close();
      return report();
    }
    const sid = session?.sessionId;
    check('session/new 返回 sessionId', typeof sid === 'string' && sid.startsWith('session_'));
    const thinkingOpt = (session?.configOptions || []).find(o => o.id === 'thinking');
    check('configOptions 含 thinking(thought_level)', thinkingOpt != null);
    check('thinking category=thought_level', thinkingOpt?.category === 'thought_level');

    // 等待 available_commands_update(session 建立后必推一条)
    await sleep(1500);

    // ── 3. set_config_option(thinking=high) + thought chunk ──
    console.log('[3/6] set thinking=high + thought chunk 验证');
    try {
      await client.request('session/set_config_option',
        { sessionId: sid, configId: 'thinking', value: 'high' }, 10_000);
      check('set_config_option(thinking) 成功', true);
    } catch (e) {
      check('set_config_option(thinking) 成功', false, e.message);
    }
    client.updates.length = 0;
    try {
      await client.request('session/prompt',
        { sessionId: sid, prompt: [{ type: 'text', text: 'What is 17*23? Think briefly, then answer.' }] },
        TURN_TIMEOUT);
      check('session/prompt 返回 stopReason', true);
    } catch (e) {
      check('session/prompt', false, e.message);
    }
    const c = client.counts();
    check('收到 agent_thought_chunk(思考区透出)', (c.agent_thought_chunk || 0) > 0, 'counts=' + JSON.stringify(c));
    check('收到 agent_message_chunk(正文)', (c.agent_message_chunk || 0) > 0);
    const thoughtText = client.updates
      .filter(u => u.sessionUpdate === 'agent_thought_chunk')
      .map(u => u.content?.text).join('');
    check('thought chunk content.type=text', client.updates.some(u => u.sessionUpdate === 'agent_thought_chunk' && u.content?.type === 'text'));
    check('thought 文本非空', thoughtText.length > 0);

    // ── 4. tool_call(诱导 shell 工具) ──
    console.log('[4/6] tool_call 工具卡验证');
    client.updates.length = 0;
    try {
      await client.request('session/prompt',
        { sessionId: sid, prompt: [{ type: 'text', text: 'Use the shell to run: echo acp-probe' }] },
        TURN_TIMEOUT);
    } catch (e) {
      check('tool prompt', false, e.message);
    }
    const c2 = client.counts();
    const hasToolCall = (c2.tool_call || 0) > 0;
    check('收到 tool_call', hasToolCall, 'counts=' + JSON.stringify(c2));
    if (hasToolCall) {
      const tc = client.updates.find(u => u.sessionUpdate === 'tool_call');
      check('tool_call 带 toolCallId', typeof tc.toolCallId === 'string');
      check('tool_call 带 title', typeof tc.title === 'string');
      check('tool_call 带 kind', typeof tc.kind === 'string');
      check('收到 tool_call_update(REPLACE 语义)', (c2.tool_call_update || 0) > 0);
    }

    // ── 5. session_info_update.title ──
    console.log('[5/6] session_info_update.title 验证');
    const titleUpdate = client.allSeen.find(u => u.sessionUpdate === 'session_info_update');
    check('收到 session_info_update.title', titleUpdate != null && typeof titleUpdate.title === 'string');

    // ── 6. 跨进程 session/load 续接 ──
    console.log('[6/6] 跨进程 session/load 续接');
    client.close();
    await sleep(1000);
    const client2 = new KimiAcpClient();
    try {
      await client2.request('initialize', {
        protocolVersion: 1,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      }, HANDSHAKE_TIMEOUT);
      let loaded;
      try {
        loaded = await client2.request('session/load', { sessionId: sid, cwd: CWD, mcpServers: [] }, HANDSHAKE_TIMEOUT);
        check('session/load 跨进程成功', loaded != null);
        check('load 返回 configOptions 快照', Array.isArray(loaded?.configOptions));
      } catch (e) {
        check('session/load 跨进程成功', false, e.message);
      }
    } finally {
      client2.close();
    }
  } finally {
    try { client.close(); } catch {}
  }
  return report();
}

function report() {
  console.log(`\n结果: ${pass} 通过, ${fail} 失败`);
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => { console.error('验证脚本异常:', e); process.exit(1); });
