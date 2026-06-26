import { claudeChannelDescriptor } from './claude-channel.js';
import { codexChannelDescriptor } from './codex-channel.js';
import { opencodeChannelDescriptor } from './opencode-channel.js';

export function createProviderRegistry(descriptors) {
  const providers = new Map();
  for (const descriptor of descriptors) {
    const provider = normalizeProvider(descriptor?.provider);
    if (providers.has(provider)) {
      throw new Error(`Duplicate provider descriptor: ${provider}`);
    }
    providers.set(provider, descriptor);
  }

  return {
    has(provider) {
      return providers.has(normalizeProvider(provider));
    },
    require(provider) {
      const normalizedProvider = normalizeProvider(provider);
      const descriptor = providers.get(normalizedProvider);
      if (!descriptor) {
        throw new Error(`Unknown provider: ${normalizedProvider}`);
      }
      return descriptor;
    },
    commands(provider) {
      return [...this.require(provider).commands];
    },
    async dispatch(provider, command, args, stdinData) {
      const descriptor = this.require(provider);
      if (!descriptor.commands.includes(command)) {
        throw new Error(`Unsupported command for ${descriptor.provider}: ${command}`);
      }
      return descriptor.handle(command, args, stdinData);
    },
  };
}

export function getDefaultProviderRegistry() {
  return createProviderRegistry([
    claudeChannelDescriptor,
    codexChannelDescriptor,
    opencodeChannelDescriptor,
  ]);
}

function normalizeProvider(provider) {
  return String(provider || '').trim().toLowerCase();
}
