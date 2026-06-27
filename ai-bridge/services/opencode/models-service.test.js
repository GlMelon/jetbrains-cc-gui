import test from 'node:test';
import assert from 'node:assert/strict';

import { listModels } from './models-service.js';

/**
 * §15.8 §11:OpenCode 模型列表查询(能力层)。
 * config.providers() 真实格式:{providers:[{id, models:{modelId:{name,capabilities,limit}}}], default}
 * 扁平化为 [{provider, model, name, contextLimit, reasoning, attachment, toolcall}]。
 * 用 mock clientFactory 注入,不依赖真实 serve。
 */
function makeMockClient(providersData) {
    return {
        config: {
            providers: async () => ({ data: providersData })
        }
    };
}

test('listModels flattens providers/models into a model list', async () => {
    const client = makeMockClient({
        providers: [{
            id: 'opencode',
            name: 'OpenCode Zen',
            models: {
                'mimo-v2.5-free': {
                    id: 'mimo-v2.5-free', providerID: 'opencode', name: 'MiMo V2.5 Free',
                    capabilities: { reasoning: true, attachment: true, toolcall: false },
                    limit: { context: 200000, output: 32000 }
                }
            }
        }],
        default: 'opencode'
    });
    const written = [];
    await listModels({ baseUrl: 'http://x' }, { clientFactory: async () => client, write: (o) => written.push(o) });
    assert.equal(written.length, 1);
    assert.equal(written[0].success, true);
    assert.equal(written[0].models.length, 1);
    assert.deepEqual(written[0].models[0], {
        provider: 'opencode', model: 'mimo-v2.5-free', name: 'MiMo V2.5 Free',
        contextLimit: 200000, reasoning: true, attachment: true, toolcall: false
    });
});

test('listModels aggregates models across multiple providers', async () => {
    const client = makeMockClient({
        providers: [
            { id: 'opencode', models: { 'm1': { name: 'M1', capabilities: {}, limit: {} } } },
            { id: 'anthropic', models: { 'claude-sonnet': { name: 'Sonnet', capabilities: { reasoning: true }, limit: { context: 200000 } } } }
        ]
    });
    const written = [];
    await listModels({ baseUrl: 'http://x' }, { clientFactory: async () => client, write: (o) => written.push(o) });
    assert.equal(written[0].models.length, 2);
    assert.ok(written[0].models.some((m) => m.provider === 'anthropic' && m.model === 'claude-sonnet' && m.reasoning === true));
});

test('listModels handles empty providers', async () => {
    const client = makeMockClient({ providers: [] });
    const written = [];
    await listModels({ baseUrl: 'http://x' }, { clientFactory: async () => client, write: (o) => written.push(o) });
    assert.equal(written[0].success, true);
    assert.deepEqual(written[0].models, []);
});

test('listModels falls back to model id when name missing', async () => {
    const client = makeMockClient({ providers: [{ id: 'opencode', models: { 'raw-id': { capabilities: {}, limit: {} } } }] });
    const written = [];
    await listModels({ baseUrl: 'http://x' }, { clientFactory: async () => client, write: (o) => written.push(o) });
    assert.equal(written[0].models[0].name, 'raw-id');
});

test('listModels emits error NDJSON on failure', async () => {
    const client = { config: { providers: async () => { throw new Error('boom'); } } };
    const written = [];
    await listModels({ baseUrl: 'http://x' }, { clientFactory: async () => client, write: (o) => written.push(o) });
    assert.equal(written[0].success, false);
    assert.ok(written[0].error.includes('boom'));
});
