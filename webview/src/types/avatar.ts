import type { ProviderType } from '../generated/protocol';
import { PROVIDER_TYPE } from '../generated/protocol';

export const AVATAR_ROLE = {
  ASSISTANT: 'assistant',
  USER: 'user',
} as const;

export const AVATAR_MODE = {
  PROVIDER: 'provider',
  PRESET: 'preset',
  CUSTOM: 'custom',
} as const;

export const AVATAR_PRESET = {
  ASSISTANT_DEFAULT: 'assistant-default',
  USER_DEFAULT: 'user-default',
} as const;

export type AvatarRole = typeof AVATAR_ROLE[keyof typeof AVATAR_ROLE];


export interface AssistantAvatarSelection {
  mode: typeof AVATAR_MODE[keyof typeof AVATAR_MODE];
  preset?: typeof AVATAR_PRESET.ASSISTANT_DEFAULT | ProviderType;
  custom?: { id: string; mimeType: string; dataUrl: string };
}

export interface UserAvatarSelection {
  mode: typeof AVATAR_MODE.PRESET | typeof AVATAR_MODE.CUSTOM;
  preset?: typeof AVATAR_PRESET.USER_DEFAULT;
  custom?: { id: string; mimeType: string; dataUrl: string };
}



export interface AvatarConfig {
  assistant: AssistantAvatarSelection;
  user: UserAvatarSelection;
  assistantPresetOptions?: { value: AssistantAvatarSelection['preset']; label: string }[];
}

export function isProviderAvatarPreset(value: unknown): value is ProviderType {
  return (
    value === PROVIDER_TYPE.CLAUDE ||
    value === PROVIDER_TYPE.CODEX ||
    value === PROVIDER_TYPE.OPENCODE
  );
}
