import assert from 'node:assert/strict';
import {
  createProviderRegistry,
  getDefaultProviderRegistry,
} from './provider-registry.js';

const noopDescriptor = (provider) => ({
  provider,
  commands: ['send'],
  handle: async () => {},
});

{
  const claude = noopDescriptor('claude');
  const registry = createProviderRegistry([claude]);

  assert.equal(registry.require('claude'), claude);
  assert.equal(registry.has('claude'), true);
  assert.deepEqual(registry.commands('claude'), ['send']);
}

assert.throws(
  () => createProviderRegistry([
    noopDescriptor('claude'),
    noopDescriptor('CLAUDE'),
  ]),
  /Duplicate provider descriptor: claude/,
);

{
  const registry = getDefaultProviderRegistry();
  assert.equal(registry.has('claude'), true);
  assert.equal(registry.has('codex'), true);
  assert.ok(registry.commands('claude').includes('send'));
  assert.ok(registry.commands('codex').includes('send'));
}

await assert.rejects(
  () => createProviderRegistry([]).dispatch('missing', 'send', [], null),
  /Unknown provider: missing/,
);

await assert.rejects(
  () => createProviderRegistry([noopDescriptor('claude')]).dispatch('claude', 'missing', [], null),
  /Unsupported command for claude: missing/,
);

{
  const calls = [];
  const descriptor = {
    provider: 'claude',
    commands: ['send'],
    handle: async (command, args, stdinData) => {
      calls.push({ command, args, stdinData });
      return 'ok';
    },
  };
  const registry = createProviderRegistry([descriptor]);

  const result = await registry.dispatch('claude', 'send', ['a'], { message: 'hi' });

  assert.equal(result, 'ok');
  assert.deepEqual(calls, [{
    command: 'send',
    args: ['a'],
    stdinData: { message: 'hi' },
  }]);
}

console.log('provider-registry.test.js passed');
