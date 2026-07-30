/**
 * ChatInputBox component module exports
 * Feature: 004-refactor-input-box
 */

export { ChatInputBox } from './ChatInputBox';

// Export types
export type {
  Attachment,
  ChatInputBoxHandle,
  ChatInputBoxProps,
  ButtonAreaProps,
  TokenIndicatorProps,
  AttachmentListProps,
  PermissionMode,
  DropdownItemData,
  DropdownPosition,
  TriggerQuery,
  FileItem,
  CommandItem,
} from './types';

// Export constants
export {
  AVAILABLE_MODES,
  IMAGE_MEDIA_TYPES,
  isImageAttachment,
} from './types';
