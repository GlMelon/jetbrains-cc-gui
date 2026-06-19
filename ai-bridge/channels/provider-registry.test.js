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

console.log('provider-registry.test.js passed');
