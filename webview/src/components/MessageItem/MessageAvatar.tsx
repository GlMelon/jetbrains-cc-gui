import { memo } from 'react';
import { ProviderModelIcon } from '../shared/ProviderModelIcon';
import type { AvatarConfig } from '../../types/avatar';
import { AVATAR_MODE, AVATAR_PRESET, isProviderAvatarPreset } from '../../types/avatar';

type MessageType = 'user' | 'assistant' | 'error' | 'notification' | 'task_notification' | string;

interface MessageAvatarProps {
  type: MessageType;
  className?: string;
  currentProvider?: string;
  avatarConfig?: AvatarConfig | null;
  /** i18n label shown under the user avatar. Defaults to 你. */
  userLabel?: string;
  /** Provider display name shown under the assistant avatar. Defaults to Claude. */
  assistantLabel?: string;
}

/**
 * 用户头像 SVG
 */
export const UserAvatarIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </svg>
);

/**
 * AI 头像 SVG (Claude logo style)
 */
export const AssistantAvatarIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 2L2 7l10 5 10-5-10-5z" />
    <path d="M2 17l10 5 10-5" />
    <path d="M2 12l10 5 10-5" />
  </svg>
);

/**
 * 消息头像组件
 * 用户消息显示紫色渐变头像，AI回复显示蓝紫渐变头像
 */
export const MessageAvatar = memo(function MessageAvatar({
  type,
  className,
  currentProvider,
  avatarConfig,
  userLabel = '你',
  assistantLabel = 'Claude',
}: MessageAvatarProps) {
  // 只有 user 和 assistant 类型显示头像
  if (type !== 'user' && type !== 'assistant') {
    return null;
  }

  const renderAssistantAvatar = () => {
    const selection = avatarConfig?.assistant;

    if (selection?.mode === AVATAR_MODE.CUSTOM && selection.custom?.dataUrl) {
      return <img className="message-avatar-image" src={selection.custom.dataUrl} alt="" />;
    }

    if (selection?.mode === AVATAR_MODE.PROVIDER && currentProvider) {
      return (
        <span className="message-avatar-provider-icon">
          <ProviderModelIcon providerId={currentProvider} size={20} colored />
        </span>
      );
    }

    if (selection?.mode === AVATAR_MODE.PRESET && isProviderAvatarPreset(selection.preset)) {
      return (
        <span className="message-avatar-provider-icon">
          <ProviderModelIcon providerId={selection.preset} size={20} colored />
        </span>
      );
    }

    if (selection?.mode === AVATAR_MODE.PRESET && selection.preset === AVATAR_PRESET.ASSISTANT_DEFAULT) {
      return <AssistantAvatarIcon />;
    }

    return <AssistantAvatarIcon />;
  };

  const renderUserAvatar = () => {
    const selection = avatarConfig?.user;

    if (selection?.mode === AVATAR_MODE.CUSTOM && selection.custom?.dataUrl) {
      return <img className="message-avatar-image" src={selection.custom.dataUrl} alt="" />;
    }

    return <UserAvatarIcon />;
  };

  return (
    <div className={`message-avatar${className ? ` ${className}` : ''}`}>
      {type === 'user' ? renderUserAvatar() : renderAssistantAvatar()}
      <span className="avatar-label">
        {type === 'user' ? userLabel : assistantLabel}
      </span>
    </div>
  );
});
