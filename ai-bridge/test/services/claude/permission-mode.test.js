import test from 'node:test';
import assert from 'node:assert/strict';

import { createPreToolUseHook } from '../../../services/claude/permission-mode.js';
import { validateHookOutput, assertSdkAcceptsHookOutput } from '../../../services/claude/permission-mode-schema.js';

// Wrap the hook so every test invocation also runs hook-shape validation.
// This is the regression guard for PR #1121 → #1126 → #1213: returning a value
// the CLI's hook schema rejects (e.g. permissionDecision: 'continue', which is
// not in HookPermissionDecision = 'allow'|'deny'|'ask'|'defer').
function makeHook(mode = 'default', cwd = '/tmp/test-cwd') {
  const raw = createPreToolUseHook({ value: mode }, cwd);
  return async (input) => {
    const result = await raw(input);
    assertSdkAcceptsHookOutput(result);
    return result;
  };
}

test('default mode: Bash returns "ask" so settings.json allow-rules cannot silently approve commands', async () => {
  const hook = makeHook('default');
  const result = await hook({
    tool_name: 'Bash',
    tool_input: { command: 'rm something.txt' },
  });
  // Hook 'ask' takes precedence over settings.json allow-rules. This is deliberate:
  // settingSources includes 'project' and 'local', whose .claude/settings.json is
  // attacker-controllable when a malicious repo is opened, so a yield here would let
  // such an allow-rule auto-run Bash silently. The 'ask' path emits the can_use_tool
  // control request, which reaches canUseTool -> the Java dialog, whose "Always allow"
  // is remembered at tool level (confirm once per tool per conversation).
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'ask');
});

test('default mode: Write returns "ask" so unmatched writes reach canUseTool / Java permissions', async () => {
  const hook = makeHook('default');
  const result = await hook({
    tool_name: 'Write',
    tool_input: { file_path: '/tmp/test-cwd/out.txt', content: 'hello' },
  });
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'ask');
});

test('default mode: Read yields "continue" so deny rules like Read(./.env) can fire', async () => {
  const hook = makeHook('default');
  const result = await hook({
    tool_name: 'Read',
    tool_input: { file_path: '/tmp/test-cwd/.env' },
  });
  assert.equal(result?.continue, true);
});

test('default mode: Grep yields "continue"', async () => {
  const hook = makeHook('default');
  const result = await hook({
    tool_name: 'Grep',
    tool_input: { pattern: 'foo' },
  });
  assert.equal(result?.continue, true);
});

test('default mode: read-only helper tools (BashOutput, NotebookRead) yield "continue" instead of prompting', async () => {
  const hook = makeHook('default');
  for (const [toolName, toolInput] of [
    ['BashOutput', { bash_id: 'shell-1' }],
    ['NotebookRead', { notebook_path: '/tmp/test-cwd/nb.ipynb' }],
  ]) {
    const result = await hook({ tool_name: toolName, tool_input: toolInput });
    assert.equal(result?.continue, true, `expected ${toolName} to yield to the CLI`);
  }
});

test('native auto mode: Bash yields "continue" so the CLI native reviewer handles it', async () => {
  const hook = makeHook('auto');
  const result = await hook({
    tool_name: 'Bash',
    tool_input: { command: 'date' },
  });
  assert.equal(result?.continue, true);
});

test('bypassPermissions mode: Bash yields "continue" (CLI mode-check auto-allows)', async () => {
  const hook = makeHook('bypassPermissions');
  const result = await hook({
    tool_name: 'Bash',
    tool_input: { command: 'date' },
  });
  assert.equal(result?.continue, true);
});

test('acceptEdits mode: Edit inside CWD yields "continue" (CLI mode-check auto-accepts)', async () => {
  const cwd = '/tmp/test-cwd';
  const hook = makeHook('acceptEdits', cwd);
  const result = await hook({
    tool_name: 'Edit',
    tool_input: { file_path: `${cwd}/src/file.js`, old_string: 'a', new_string: 'b' },
  });
  assert.equal(result?.continue, true);
});

test('acceptEdits mode: Edit outside CWD yields "continue"', async () => {
  const hook = makeHook('acceptEdits', '/tmp/test-cwd');
  const result = await hook({
    tool_name: 'Edit',
    tool_input: { file_path: '/etc/passwd', old_string: 'a', new_string: 'b' },
  });
  assert.equal(result?.continue, true);
});

test('acceptEdits mode: Bash returns "ask" (acceptEdits auto-accepts edits only, not command execution)', async () => {
  const hook = makeHook('acceptEdits');
  const result = await hook({
    tool_name: 'Bash',
    tool_input: { command: 'rm x' },
  });
  // Security fix (F/B): acceptEdits must not auto-run shell commands, and a project
  // allow-rule must not auto-approve Bash execution either.
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'ask');
});

test('default mode: known read-only MCP tools yield "continue"', async () => {
  const hook = makeHook('default');
  for (const toolName of [
    'mcp__ace-tool__search_context',
    'mcp__context7__query-docs',
    'mcp__time__get_current_time',
  ]) {
    const result = await hook({ tool_name: toolName, tool_input: { query: 'x' } });
    assert.equal(result?.continue, true, `expected ${toolName} to yield to the CLI`);
  }
});

test('default mode: unknown or compound MCP tools return "ask"', async () => {
  const hook = makeHook('default');
  // Names lacking "Write"/"Edit" (delete_file, run_command, exec) must NOT be treated as
  // read-only: the old blocklist heuristic let them yield to the CLI, where a project/local
  // .claude/settings.json allow-rule (attacker-controllable) could auto-approve them silently.
  // Compound names whose action merely STARTS with a read-only verb (search_docs,
  // read_and_delete) also stay read-only under the positive verb allowlist, so they are
  // covered by the "known read-only" test above instead.
  for (const toolName of ['mcp__fs__delete_file', 'mcp__shell__run_command', 'mcp__db__execute', 'mcp__some-server__some-tool']) {
    const result = await hook({ tool_name: toolName, tool_input: {} });
    assert.equal(result?.hookSpecificOutput?.permissionDecision, 'ask', `expected ${toolName} to ask`);
  }
});

test('EnterPlanMode is still auto-allowed (mode transition signal)', async () => {
  const hook = makeHook('default');
  const result = await hook({
    tool_name: 'EnterPlanMode',
    tool_input: {},
  });
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'allow');
});

test('plan mode: SAFE tool (Read) yields "continue" so deny rules apply in plan mode', async () => {
  const hook = makeHook('plan');
  const result = await hook({
    tool_name: 'Read',
    tool_input: { file_path: '/tmp/test-cwd/x' },
  });
  assert.equal(result?.continue, true);
});

test('plan mode: PLAN_MODE_ALLOWED_TOOLS (WebFetch) yields "continue"', async () => {
  const hook = makeHook('plan');
  const result = await hook({
    tool_name: 'WebFetch',
    tool_input: { url: 'https://example.com', prompt: 'title' },
  });
  assert.equal(result?.continue, true);
});

test('plan mode: read-only MCP tool yields "continue"', async () => {
  const hook = makeHook('plan');
  const result = await hook({
    tool_name: 'mcp__context7__query-docs',
    tool_input: { query: 'x' },
  });
  assert.equal(result?.continue, true);
});

test('plan mode: unknown MCP tools return "ask" so read-only third-party servers stay usable', async () => {
  const hook = makeHook('plan');
  // Names cannot prove an MCP tool is side-effect free, so these must not yield to
  // the CLI — but a hard deny would make every unlisted read-only MCP server unusable
  // in plan mode. 'ask' routes to the same can_use_tool dialog plan mode already uses
  // for Edit/Write/Bash; the user decides. ('mcp__some-server__lookup' from upstream is
  // intentionally excluded: the local positive verb allowlist treats a `lookup` action
  // as read-only and yields it.)
  for (const toolName of ['mcp__fs__delete_file', 'mcp__shell__run_command', 'mcp__some-server__some-tool']) {
    const result = await hook({ tool_name: toolName, tool_input: {} });
    assert.equal(result?.hookSpecificOutput?.permissionDecision, 'ask', `expected ${toolName} to ask`);
  }
});

test('plan mode: Agent is still auto-allowed (sub-agent permission flow unchanged)', async () => {
  const hook = makeHook('plan');
  const result = await hook({
    tool_name: 'Agent',
    tool_input: { description: 'x', prompt: 'y' },
  });
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'allow');
});

test('plan mode: non-allowed tool falls through to plan-specific deny', async () => {
  const hook = makeHook('plan');
  const result = await hook({
    tool_name: 'SomeUnknownTool',
    tool_input: {},
  });
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'deny');
});

// ======== AskUserQuestion / ExitPlanMode interception (all modes) ========
// These tests verify that interactive tools are ALWAYS intercepted by the hook
// and routed through canUseTool / requestPlanApproval, regardless of permission mode.
// This ensures dialogs appear even in subagent contexts and bypassPermissions mode.

for (const mode of ['default', 'plan', 'bypassPermissions', 'acceptEdits']) {
  test(`${mode} mode: AskUserQuestion is intercepted (not YIELD_TO_CLI)`, async () => {
    let intercepted = false;
    const hook = createPreToolUseHook({ value: mode }, '/tmp/test-cwd', null, {
      canUseTool: async (toolName, toolInput) => {
        intercepted = true;
        assert.equal(toolName, 'AskUserQuestion');
        return { behavior: 'allow', updatedInput: toolInput };
      }
    });
    const result = await hook({
      tool_name: 'AskUserQuestion',
      tool_input: { questions: [{ question: 'test', header: 'h', options: [], multiSelect: false }] },
    });

    assert.equal(intercepted, true);
    assert.equal(result?.hookSpecificOutput?.permissionDecision, 'allow');
  });
}

test('default mode: ExitPlanMode is intercepted (not YIELD_TO_CLI)', async () => {
  let intercepted = false;
  const hook = createPreToolUseHook({ value: 'default' }, '/tmp/test-cwd', null, {
    requestPlanApproval: async (toolInput) => {
      intercepted = true;
      assert.equal(toolInput.plan, 'test plan');
      return { approved: false, message: 'rejected in test' };
    }
  });
  const result = await hook({
    tool_name: 'ExitPlanMode',
    tool_input: { plan: 'test plan' },
  });

  assert.equal(intercepted, true);
  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'deny');
});

// ======== hook-shape validator self-tests ========

test('mode transition callback failure restores the previous hook state', async () => {
  const permissionModeState = { value: 'default' };
  const hook = createPreToolUseHook(permissionModeState, '/tmp/test-cwd', async () => false);

  const result = await hook({
    tool_name: 'EnterPlanMode',
    tool_input: {},
  });

  assert.equal(result?.hookSpecificOutput?.permissionDecision, 'allow');
  assert.equal(permissionModeState.value, 'default');
});


// These prove the schema mirror in permission-mode-schema.js would have caught
// the PR #1121/#1126 bug that PR #1213 fixed. If they fail, the validator no
// longer guards against the historical regression.

test('schema: rejects the historical "permissionDecision: continue" bug', () => {
  const r = validateHookOutput({
    hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'continue' }
  });
  assert.equal(r.ok, false);
  assert.match(r.error, /permissionDecision/);
  assert.match(r.error, /allow.*deny.*ask.*defer/);
});

test('schema: rejects any other unknown permissionDecision enum value', () => {
  for (const bogus of ['continue', 'block', 'skip', '', null, 0, true]) {
    const r = validateHookOutput({
      hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: bogus }
    });
    assert.equal(r.ok, false, `expected ${JSON.stringify(bogus)} to be rejected`);
  }
});

test('schema: rejects hookSpecificOutput missing hookEventName', () => {
  const r = validateHookOutput({ hookSpecificOutput: { permissionDecision: 'allow' } });
  assert.equal(r.ok, false);
  assert.match(r.error, /hookEventName/);
});

test('schema: rejects unknown top-level keys (catches typos like "permissionDescision")', () => {
  const r = validateHookOutput({ permissionDescision: 'allow' });
  assert.equal(r.ok, false);
  assert.match(r.error, /permissionDescision/);
});

test('schema: accepts the four valid HookPermissionDecision values', () => {
  for (const valid of ['allow', 'deny', 'ask', 'defer']) {
    const r = validateHookOutput({
      hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: valid }
    });
    assert.equal(r.ok, true, `expected ${valid} to be accepted, got: ${r.error}`);
  }
});

test('schema: accepts top-level continue:true (the yield-to-CLI shape)', () => {
  assert.equal(validateHookOutput({ continue: true }).ok, true);
});

test('schema: accepts undefined / empty / null (CLI treats as no-opinion)', () => {
  assert.equal(validateHookOutput(undefined).ok, true);
  assert.equal(validateHookOutput(null).ok, true);
  assert.equal(validateHookOutput({}).ok, true);
});
