import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MODEL_TIER_SYNC_ENV_KEYS,
  setModelEnvironmentVariables,
} from './model-utils.js';

test('settings env sync writes configured tiers and clears unconfigured ones', () => {
  const prev = { ...process.env };
  try {
    // 预置残留:上一家供应商写入的 fable 档位在本家 settings 中未配置,必须被清除。
    process.env.ANTHROPIC_DEFAULT_FABLE_MODEL = 'stale-legacy-model';

    setModelEnvironmentVariables('glm-5.3', 'claude-role-sonnet', {
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-5.3',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-5.3-air',
      ANTHROPIC_SMALL_FAST_MODEL: 'glm-5.3-mini',
    });

    assert.equal(process.env.ANTHROPIC_MODEL, 'glm-5.3');
    assert.equal(process.env.ANTHROPIC_DEFAULT_SONNET_MODEL, 'glm-5.3');
    assert.equal(process.env.ANTHROPIC_DEFAULT_OPUS_MODEL, 'glm-5.3-air');
    assert.equal(process.env.ANTHROPIC_SMALL_FAST_MODEL, 'glm-5.3-mini');
    assert.equal(process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL, undefined);
    assert.equal(process.env.ANTHROPIC_DEFAULT_FABLE_MODEL, undefined);
    assert.equal(process.env.CLAUDE_CODE_SUBAGENT_MODEL, undefined);
  } finally {
    for (const key of MODEL_TIER_SYNC_ENV_KEYS) {
      if (prev[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prev[key];
      }
    }
    if (prev.ANTHROPIC_MODEL === undefined) {
      delete process.env.ANTHROPIC_MODEL;
    } else {
      process.env.ANTHROPIC_MODEL = prev.ANTHROPIC_MODEL;
    }
  }
});

test('current tier is still written when no settings env is provided', () => {
  const prev = { ...process.env };
  try {
    delete process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL;

    setModelEnvironmentVariables('kimi-k5-haiku', 'claude-role-haiku');

    assert.equal(process.env.ANTHROPIC_DEFAULT_HAIKU_MODEL, 'kimi-k5-haiku');
    assert.equal(process.env.ANTHROPIC_MODEL, 'kimi-k5-haiku');
  } finally {
    for (const key of [...MODEL_TIER_SYNC_ENV_KEYS, 'ANTHROPIC_MODEL']) {
      if (prev[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prev[key];
      }
    }
  }
});

test('non-object settings env is ignored and tiers keep current behavior', () => {
  const prev = { ...process.env };
  try {
    setModelEnvironmentVariables('deepseek-v4', 'some-custom-model', 'not-an-object');

    assert.equal(process.env.ANTHROPIC_MODEL, 'deepseek-v4');
    assert.equal(process.env.ANTHROPIC_DEFAULT_SONNET_MODEL, 'deepseek-v4');
  } finally {
    for (const key of [...MODEL_TIER_SYNC_ENV_KEYS, 'ANTHROPIC_MODEL']) {
      if (prev[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prev[key];
      }
    }
  }
});
