import { useCallback, useEffect, useState } from 'react';
import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { AVATAR_CONFIG_APPLIED_EVENT } from '../bootstrap/avatar';
import type { AssistantAvatarSelection, AvatarConfig, AvatarRole, UserAvatarSelection } from '../types/avatar';
import { AVATAR_ROLE } from '../types/avatar';

export interface UseAvatarConfigReturn {
  avatarConfig: AvatarConfig | null;
  setAssistantAvatarSelection: (selection: AssistantAvatarSelection) => void;
  setUserAvatarSelection: (selection: UserAvatarSelection) => void;
  uploadAssistantAvatar: () => void;
  uploadUserAvatar: () => void;
}

function isAvatarConfig(value: unknown): value is AvatarConfig {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<AvatarConfig>;
  return Boolean(candidate.assistant && candidate.user);
}

export function useAvatarConfig(): UseAvatarConfigReturn {
  const [avatarConfig, setAvatarConfig] = useState<AvatarConfig | null>(null);

  useEffect(() => {
    const handleAvatarConfig = (event: Event) => {
      const detail = (event as CustomEvent<unknown>).detail;
      if (isAvatarConfig(detail)) {
        setAvatarConfig(detail);
      }
    };

    window.addEventListener(AVATAR_CONFIG_APPLIED_EVENT, handleAvatarConfig);
    sendAction(UPSTREAM.AVATAR_GET_CONFIG, {});

    return () => window.removeEventListener(AVATAR_CONFIG_APPLIED_EVENT, handleAvatarConfig);
  }, []);

  const setAssistantAvatarSelection = useCallback((selection: AssistantAvatarSelection) => {
    setAvatarConfig((current) => {
      if (!current) return current;
      const nextConfig: AvatarConfig = { ...current, assistant: selection };
      sendAction(UPSTREAM.AVATAR_SET_CONFIG, nextConfig);
      return nextConfig;
    });
  }, []);

  const setUserAvatarSelection = useCallback((selection: UserAvatarSelection) => {
    setAvatarConfig((current) => {
      if (!current) return current;
      const nextConfig: AvatarConfig = { ...current, user: selection };
      sendAction(UPSTREAM.AVATAR_SET_CONFIG, nextConfig);
      return nextConfig;
    });
  }, []);

  const uploadCustomAvatar = useCallback((role: AvatarRole) => {
    sendAction(UPSTREAM.AVATAR_UPLOAD_CUSTOM, { role });
  }, []);

  const uploadAssistantAvatar = useCallback(() => uploadCustomAvatar(AVATAR_ROLE.ASSISTANT), [uploadCustomAvatar]);
  const uploadUserAvatar = useCallback(() => uploadCustomAvatar(AVATAR_ROLE.USER), [uploadCustomAvatar]);

  return {
    avatarConfig,
    setAssistantAvatarSelection,
    setUserAvatarSelection,
    uploadAssistantAvatar,
    uploadUserAvatar,
  };
}
