import { useCallback } from 'react';
import type { Attachment } from '../types.js';
import { generateId } from '../utils/generateId.js';
import { debugError } from '../../../utils/debug.js';



/**
 * useAttachmentHandlers - Handle attachment add/remove
 *
 * Supports both controlled (external) and uncontrolled (internal) attachment modes.
 */
export function useAttachmentHandlers({
  externalAttachments,
  onAddAttachment,
  onRemoveAttachment,
  setInternalAttachments,
}: {
  externalAttachments?: Attachment[];
  onAddAttachment?: (files: FileList) => void;
  onRemoveAttachment?: (id: string) => void;
  setInternalAttachments: React.Dispatch<React.SetStateAction<Attachment[]>>;
}) {
  const handleAddAttachment = useCallback(
    (files: FileList) => {
      if (externalAttachments !== undefined) {
        onAddAttachment?.(files);
        return;
      }

      Array.from(files).forEach((file) => {
        const reader = new FileReader();
        reader.onload = () => {
          const result = reader.result;
          if (typeof result !== 'string') return;
          const commaIndex = result.indexOf(',');
          if (commaIndex === -1) return;
          const base64 = result.substring(commaIndex + 1);
          const attachment: Attachment = {
            id: generateId(),
            fileName: file.name,
            mediaType: file.type || 'application/octet-stream',
            data: base64,
          };
          setInternalAttachments((prev: Attachment[]) => [...prev, attachment]);
        };
        reader.onerror = () => {
          debugError('[useAttachmentHandlers] Failed to read file:', file.name);
        };
        reader.onabort = () => {
          debugError('[useAttachmentHandlers] File read aborted:', file.name);
        };
        reader.readAsDataURL(file);
      });
    },
    [externalAttachments, onAddAttachment, setInternalAttachments]
  );

  const handleRemoveAttachment = useCallback(
    (id: string) => {
      if (externalAttachments !== undefined) {
        onRemoveAttachment?.(id);
        return;
      }
      setInternalAttachments((prev: Attachment[]) => prev.filter((a: Attachment) => a.id !== id));
    },
    [externalAttachments, onRemoveAttachment, setInternalAttachments]
  );

  return { handleAddAttachment, handleRemoveAttachment };
}

