import test from 'node:test';
import assert from 'node:assert/strict';

import { splitModel } from './model-utils.js';

/**
 * §15.7 B2:OpenCode 模型聚合器字符串拆分。
 * OpenCode 用 `provider/model` 聚合格式(背后 models.dev),与 Claude/Codex 单一 provider 不同。
 * SDK session.prompt 需要 {providerID, modelID} 对象,故须从字符串拆出。
 */
test('splitModel splits provider/model into providerID and modelID', () => {
    assert.deepEqual(splitModel('anthropic/claude-3-5-sonnet'), {
        providerID: 'anthropic',
        modelID: 'claude-3-5-sonnet'
    });
    assert.deepEqual(splitModel('opencode/mimo-v2.5-free'), {
        providerID: 'opencode',
        modelID: 'mimo-v2.5-free'
    });
});

test('splitModel handles model IDs containing slashes (nested path)', () => {
    // 模型 id 本身可能含 /,只拆首个 / 作为 provider 分隔
    const r = splitModel('requesty/xai/grok-4');
    assert.equal(r.providerID, 'requesty');
    assert.equal(r.modelID, 'xai/grok-4');
});

test('splitModel trims whitespace', () => {
    assert.deepEqual(splitModel('  anthropic/claude-3-5-sonnet  '), {
        providerID: 'anthropic',
        modelID: 'claude-3-5-sonnet'
    });
});

test('splitModel returns null providerID when no slash present', () => {
    // 无 / 的纯 model id:无法判定 provider,返 providerID=null 交由上游决策
    assert.deepEqual(splitModel('claude-3-5-sonnet'), {
        providerID: null,
        modelID: 'claude-3-5-sonnet'
    });
});

test('splitModel returns nulls for empty/blank input', () => {
    assert.deepEqual(splitModel(''), { providerID: null, modelID: null });
    assert.deepEqual(splitModel('   '), { providerID: null, modelID: null });
    assert.deepEqual(splitModel(null), { providerID: null, modelID: null });
    assert.deepEqual(splitModel(undefined), { providerID: null, modelID: null });
});
