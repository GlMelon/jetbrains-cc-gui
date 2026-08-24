// @ts-check
import { claudeChannelDescriptor } from './claude-channel.js';
import { codexChannelDescriptor } from './codex-channel.js';
import { opencodeChannelDescriptor } from './opencode-channel.js';
import { grokChannelDescriptor } from './grok-channel.js';
import { kimiChannelDescriptor } from './kimi-channel.js';
import { piChannelDescriptor } from './pi-channel.js';
import { ompChannelDescriptor } from './omp-channel.js';
import { dshChannelDescriptor } from './dsh-channel.js';

/**
 * Provider channel descriptor:provider 名 + 支持命令列表 + dispatch 入口。
 * @typedef {{
 *   provider: string;
 *   commands: string[];
 *   handle: (command: string, args: string[], stdinData: Record<string, any> | null) => Promise<void> | void;
 * }} ChannelDescriptor
 */

/**
 * Provider registry:按 normalized provider 名路由命令到对应 channel descriptor。
 * @typedef {{
 *   has: (provider: string | undefined) => boolean;
 *   require: (provider: string | undefined) => ChannelDescriptor;
 *   commands: (provider: string | undefined) => string[];
 *   dispatch: (provider: string | undefined, command: string, args: string[], stdinData: Record<string, any> | null) => Promise<void>;
 * }} ProviderRegistry
 */

/**
 * @param {ChannelDescriptor[]} descriptors
 * @returns {ProviderRegistry}
 */
export function createProviderRegistry(descriptors) {
  /** @type {Map<string, ChannelDescriptor>} */
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

/** @returns {ProviderRegistry} */
export function getDefaultProviderRegistry() {
  return createProviderRegistry([
    claudeChannelDescriptor,
    codexChannelDescriptor,
    opencodeChannelDescriptor,
    grokChannelDescriptor,
    kimiChannelDescriptor,
    piChannelDescriptor,
    ompChannelDescriptor,
    dshChannelDescriptor,
  ]);
}

/**
 * @param {string | undefined | null} provider
 * @returns {string}
 */
function normalizeProvider(provider) {
  return String(provider || '').trim().toLowerCase();
}
