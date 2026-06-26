import test from 'node:test';
import assert from 'node:assert/strict';

import {
  mapModelIdToSdkName,
  resolveModelFromSettings,
  resolveClaudeEnhanceModelName,
  setModelEnvironmentVariables,
  modelSupportsVision,
} from './model-utils.js';

// --- mapModelIdToSdkName ------------------------------------------------

test('mapModelIdToSdkName maps Claude families to short SDK names', () => {
  assert.equal(mapModelIdToSdkName('claude-role-sonnet'), 'sonnet');
  assert.equal(mapModelIdToSdkName('claude-role-opus'), 'opus');
  assert.equal(mapModelIdToSdkName('claude-role-fable'), 'opus');
  assert.equal(mapModelIdToSdkName('claude-role-haiku'), 'haiku');
  // Unknown / explicit IDs fall back to sonnet (because the SDK uses
  // ANTHROPIC_DEFAULT_SONNET_MODEL as the lookup target for arbitrary names).
  assert.equal(mapModelIdToSdkName('mimo-v2.5-pro'), 'sonnet');
  assert.equal(mapModelIdToSdkName(''), 'sonnet');
  assert.equal(mapModelIdToSdkName(null), 'sonnet');
});

// --- resolveModelFromSettings -------------------------------------------

test('resolveModelFromSettings returns original when no settings env provided', () => {
  assert.equal(resolveModelFromSettings('claude-sonnet-4-6', null), 'claude-sonnet-4-6');
  assert.equal(resolveModelFromSettings('claude-sonnet-4-6', {}), 'claude-sonnet-4-6');
});

test('resolveModelFromSettings applies model-specific settings mapping', () => {
  const env = {
    ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-4.7-opus',
    ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7',
    ANTHROPIC_DEFAULT_HAIKU_MODEL: 'glm-4.7-flash',
    ANTHROPIC_DEFAULT_FABLE_MODEL: 'claude-fable-5',
  };
  assert.equal(resolveModelFromSettings('claude-role-opus', env), 'glm-4.7-opus');
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env), 'glm-4.7');
  assert.equal(resolveModelFromSettings('claude-role-haiku', env), 'glm-4.7-flash');
  assert.equal(resolveModelFromSettings('claude-role-fable', env), 'claude-fable-5');
});

test('resolveModelFromSettings prefers request actualModel over settings env', () => {
  const env = {
    ANTHROPIC_MODEL: 'mimo-v2.5',
    ANTHROPIC_DEFAULT_SONNET_MODEL: 'ignored-sonnet',
  };
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env, 'glm5.2'), 'glm5.2');
});

test('resolveModelFromSettings applies request-owned [1m] suffix to actualModel', () => {
  const env = {
    ANTHROPIC_MODEL: 'ignored-global',
  };
  assert.equal(resolveModelFromSettings('claude-role-sonnet[1m]', env, 'glm5.2'), 'glm5.2[1m]');
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env, 'glm5.2[1M]'), 'glm5.2');
});

test('resolveModelFromSettings falls back from Fable role to Opus then global model', () => {
  assert.equal(
    resolveModelFromSettings('claude-role-fable', {
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    }),
    'mimo-v2.5-pro',
  );
  assert.equal(
    resolveModelFromSettings('claude-role-fable', {
      ANTHROPIC_MODEL: 'global-fallback',
    }),
    'global-fallback',
  );
});

test('resolveModelFromSettings uses global ANTHROPIC_MODEL as fallback when no role mapping exists', () => {
  const env = {
    ANTHROPIC_MODEL: 'override-everywhere',
  };
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env), 'override-everywhere');
  assert.equal(resolveModelFromSettings('claude-role-opus', env), 'override-everywhere');
});

test('resolveModelFromSettings prefers role-specific mapping over global ANTHROPIC_MODEL', () => {
  const env = {
    ANTHROPIC_MODEL: 'fallback-model',
    ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5',
    ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-fast',
  };
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env), 'mimo-v2.5');
  assert.equal(resolveModelFromSettings('claude-role-opus', env), 'mimo-v2.5-pro');
  assert.equal(resolveModelFromSettings('claude-role-haiku', env), 'mimo-v2.5-fast');
});

test('resolveModelFromSettings preserves explicitly selected custom Claude model IDs', () => {
  const env = {
    ANTHROPIC_MODEL: 'mimo-v2.5',
    ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5',
  };
  assert.equal(resolveModelFromSettings('glm5.2', env), 'glm5.2');
});

test('resolveModelFromSettings does not map legacy concrete Claude model IDs', () => {
  const env = {
    ANTHROPIC_DEFAULT_SONNET_MODEL: 'role-sonnet',
    ANTHROPIC_DEFAULT_OPUS_MODEL: 'role-opus',
    ANTHROPIC_DEFAULT_HAIKU_MODEL: 'role-haiku',
    ANTHROPIC_DEFAULT_FABLE_MODEL: 'role-fable',
  };
  assert.equal(resolveModelFromSettings('claude-sonnet-4-6', env), 'claude-sonnet-4-6');
  assert.equal(resolveModelFromSettings('claude-opus-4-8', env), 'claude-opus-4-8');
  assert.equal(resolveModelFromSettings('claude-haiku-4-5', env), 'claude-haiku-4-5');
  assert.equal(resolveModelFromSettings('claude-fable-5', env), 'claude-fable-5');
});

test('resolveModelFromSettings ignores empty / whitespace mapping values', () => {
  const env = {
    ANTHROPIC_DEFAULT_SONNET_MODEL: '   ',
    ANTHROPIC_DEFAULT_OPUS_MODEL: '',
  };
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env), 'claude-role-sonnet');
  assert.equal(resolveModelFromSettings('claude-role-opus', env), 'claude-role-opus');
});

test('resolveModelFromSettings does NOT remap non-Anthropic model IDs', () => {
  // A third-party model name like 'qwen3-max' should pass through unchanged
  // even when ANTHROPIC_DEFAULT_SONNET_MODEL is configured. Otherwise we would
  // silently rewrite intentional model selections.
  const env = { ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7' };
  assert.equal(resolveModelFromSettings('qwen3-max', env), 'qwen3-max');
  assert.equal(resolveModelFromSettings('deepseek-v4-pro', env), 'deepseek-v4-pro');
});

// --- [1m] suffix follows the webview request state ------------------------
//
// Bug: when a user opens the 1M context toggle in the UI, the frontend sends
//   `claude-role-sonnet[1m]` to the backend. If `settings.json` contains a
//   provider mapping like `ANTHROPIC_DEFAULT_SONNET_MODEL=glm-4.7` (no [1m]),
//   the old resolver returned `'glm-4.7'`, silently dropping the suffix.
//   The Claude SDK then read the env var without [1m] and did NOT enable the
//   1M context window even though the toggle was on.
// Fix: make the request modelId the source of truth. Preserve/append [1m] when
// the toggle is on, and strip stale mapping suffixes when the toggle is off.

test('resolveModelFromSettings preserves [1m] suffix when mapping value lacks it', () => {
  const env = { ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7' };
  assert.equal(
    resolveModelFromSettings('claude-role-sonnet[1m]', env),
    'glm-4.7[1m]',
    'request asked for 1M, mapping must keep the [1m] suffix so the SDK enables 1M context'
  );
});

test('resolveModelFromSettings does not double-append [1m] when mapping already has it', () => {
  const env = { ANTHROPIC_DEFAULT_SONNET_MODEL: 'deepseek-v4-pro[1m]' };
  assert.equal(
    resolveModelFromSettings('claude-role-sonnet[1m]', env),
    'deepseek-v4-pro[1m]'
  );
});

test('resolveModelFromSettings strips stale [1m] suffix when 1M toggle is OFF', () => {
  const env = { ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7[1M]' };
  assert.equal(
    resolveModelFromSettings('claude-role-sonnet', env),
    'glm-4.7',
    'request did not ask for 1M, stale settings mapping suffix must not force it on'
  );
});

test('resolveModelFromSettings preserves [1m] across ANTHROPIC_MODEL global override', () => {
  const env = { ANTHROPIC_MODEL: 'override-model[1M]' };
  assert.equal(resolveModelFromSettings('claude-role-sonnet[1m]', env), 'override-model[1m]');
  assert.equal(resolveModelFromSettings('claude-role-sonnet', env), 'override-model');
});

test('resolveModelFromSettings preserves [1m] for opus mapping', () => {
  const env = { ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro' };
  assert.equal(resolveModelFromSettings('claude-role-opus[1m]', env), 'mimo-v2.5-pro[1m]');
});

// --- setModelEnvironmentVariables ---------------------------------------

test('setModelEnvironmentVariables sets sonnet env for sonnet-family base model', () => {
  const previous = {
    ANTHROPIC_MODEL: process.env.ANTHROPIC_MODEL,
    ANTHROPIC_DEFAULT_SONNET_MODEL: process.env.ANTHROPIC_DEFAULT_SONNET_MODEL,
  };
  try {
    delete process.env.ANTHROPIC_MODEL;
    delete process.env.ANTHROPIC_DEFAULT_SONNET_MODEL;

    setModelEnvironmentVariables('glm-4.7[1m]', 'claude-role-sonnet[1m]');

    assert.equal(process.env.ANTHROPIC_MODEL, 'glm-4.7[1m]');
    assert.equal(process.env.ANTHROPIC_DEFAULT_SONNET_MODEL, 'glm-4.7[1m]');
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

test('setModelEnvironmentVariables routes haiku base to haiku env', () => {
  const previous = {
    ANTHROPIC_MODEL: process.env.ANTHROPIC_MODEL,
    ANTHROPIC_DEFAULT_HAIKU_MODEL: process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL,
  };
  try {
    delete process.env.ANTHROPIC_MODEL;
    delete process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL;

    setModelEnvironmentVariables('glm-4.7-flash', 'claude-role-haiku');

    assert.equal(process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL, 'glm-4.7-flash');
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

// --- modelSupportsVision -------------------------------------------------

test('modelSupportsVision only matches the canonical claude- prefix', () => {
  assert.equal(modelSupportsVision('claude-sonnet-4-6'), true);
  assert.equal(modelSupportsVision('claude-opus-4-7'), true);
  // Third-party proxies that merely contain "claude" must NOT be treated as
  // native vision-capable models.
  assert.equal(modelSupportsVision('claude-compatible-proxy'), true); // starts with 'claude-'
  assert.equal(modelSupportsVision('mimo-claude-bridge'), false);
  assert.equal(modelSupportsVision('glm-4.7'), false);
  assert.equal(modelSupportsVision('deepseek-v4-pro[1m]'), false);
  assert.equal(modelSupportsVision(''), true);
  assert.equal(modelSupportsVision(null), true);
});

// --- resolveClaudeEnhanceModelName --------------------------------------
//
// Bug 3 修复:promptEnhancer(claude 路径)下发模型名必须与 chat/commitAi 同源 ——
// 优先用 registry 解析的 actualModel(具体模型 id,如 glm-5.2),而非仅靠
// mapModelIdToSdkName(role→bucket)+ settings.json env 间接解析。当 registry
// actualModel 与 settings.json env(cc-switch 写入)不同步时,旧逻辑会用错模型。
// 语义与 resolveModelFromSettings 的 actualModel 优先 + [1m] 处理一致,但回退是
// bucket name(promptEnhancer 的 SDK model 参数需要 bucket,而非 role id 原值)。

test('resolveClaudeEnhanceModelName prefers actualModel over role bucket mapping', () => {
  // actualModel=glm-5.2 必须直传,绕过 mapModelIdToSdkName('claude-role-sonnet')='sonnet'
  assert.equal(resolveClaudeEnhanceModelName('claude-role-sonnet', 'glm-5.2'), 'glm-5.2');
  assert.equal(resolveClaudeEnhanceModelName('claude-role-opus', 'mimo-v2.5-pro'), 'mimo-v2.5-pro');
});

test('resolveClaudeEnhanceModelName falls back to bucket name when actualModel absent', () => {
  // 默认 registry(actualModel 空)→ 保持旧行为(零回归)
  assert.equal(resolveClaudeEnhanceModelName('claude-role-sonnet', null), 'sonnet');
  assert.equal(resolveClaudeEnhanceModelName('claude-role-opus', undefined), 'opus');
  assert.equal(resolveClaudeEnhanceModelName('claude-role-haiku', ''), 'haiku');
  assert.equal(resolveClaudeEnhanceModelName('claude-role-sonnet', '   '), 'sonnet');
});

test('resolveClaudeEnhanceModelName appends request [1m] suffix onto actualModel', () => {
  // 用户开了 1M toggle(model 含 [1m]),actualModel 须带 [1m] 让 SDK 启用 1M context
  assert.equal(resolveClaudeEnhanceModelName('claude-role-sonnet[1m]', 'glm-5.2'), 'glm-5.2[1m]');
});

test('resolveClaudeEnhanceModelName strips stale [1m] from actualModel when 1M is off', () => {
  // request 未开 1M,actualModel 残留 [1M] 须剥离(与 resolveModelFromSettings 一致)
  assert.equal(resolveClaudeEnhanceModelName('claude-role-sonnet', 'glm-5.2[1M]'), 'glm-5.2');
});
