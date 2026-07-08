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
export type AvatarMode = typeof AVATAR_MODE[keyof typeof AVATAR_MODE];

export type AssistantAvatarMode =
  | typeof AVATAR_MODE.PROVIDER
  | typeof AVATAR_MODE.PRESET
  | typeof AVATAR_MODE.CUSTOM;

export type UserAvatarMode =
  | typeof AVATAR_MODE.PRESET
  | typeof AVATAR_MODE.CUSTOM;

export type AssistantAvatarPreset =
  | typeof AVATAR_PRESET.ASSISTANT_DEFAULT
  | ProviderType;

export type UserAvatarPreset = typeof AVATAR_PRESET.USER_DEFAULT;

export interface CustomAvatarPayload {
  id: string;
  mimeType: string;
  dataUrl: string;
}

export interface AssistantAvatarSelection {
  mode: AssistantAvatarMode;
  preset?: AssistantAvatarPreset;
  custom?: CustomAvatarPayload;
}

export interface UserAvatarSelection {
  mode: UserAvatarMode;
  preset?: UserAvatarPreset;
  custom?: CustomAvatarPayload;
}

export interface AvatarConfig {
  assistant: AssistantAvatarSelection;
  user: UserAvatarSelection;
}

export function isProviderAvatarPreset(value: unknown): value is ProviderType {
  return (
    value === PROVIDER_TYPE.CLAUDE ||
    value === PROVIDER_TYPE.CODEX ||
    value === PROVIDER_TYPE.OPENCODE
  );
}
