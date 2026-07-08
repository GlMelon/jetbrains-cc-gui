import { registerLegacyAlias } from '../bridge';
import { subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM } from '../generated/protocol';
import type { AvatarConfig } from '../types/avatar';

export const AVATAR_CONFIG_APPLIED_EVENT = 'avatar-config-applied';

export function applyAvatarConfig(rawConfig: AvatarConfig | string): void {
  let config: AvatarConfig;

  if (typeof rawConfig === 'string') {
    try {
      config = JSON.parse(rawConfig) as AvatarConfig;
    } catch (error) {
      console.error('[Main] Failed to parse avatar config:', error, rawConfig);
      return;
    }
  } else {
    config = rawConfig;
  }

  window.dispatchEvent(new CustomEvent(AVATAR_CONFIG_APPLIED_EVENT, { detail: config }));
}

export function initAvatar(): void {
  registerLegacyAlias('applyAvatarConfig', DOWNSTREAM.AVATAR_CONFIG_APPLY);
  subscribeEvent(DOWNSTREAM.AVATAR_CONFIG_APPLY, (payload) => {
    applyAvatarConfig(payload as AvatarConfig | string);
  });

  if (window.__pendingAvatarConfig) {
    applyAvatarConfig(window.__pendingAvatarConfig as AvatarConfig | string);
    delete window.__pendingAvatarConfig;
  }
}
