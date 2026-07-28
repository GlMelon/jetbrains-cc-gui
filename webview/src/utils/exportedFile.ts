import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import type { SuccessfulHistoryExportPayload } from './historyExport';

/** Saves the exact backend-rendered file; the Webview does not transform export content. */
export function downloadExportedFile(payload: SuccessfulHistoryExportPayload): void {
  const savePayload = JSON.stringify({
    content: payload.content,
    fileName: payload.fileName,
    format: payload.format,
  });

  if (sendAction(UPSTREAM.SAVE_EXPORTED_FILE, savePayload)) {
    return;
  }

  const blob = new Blob([payload.content], { type: payload.mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = payload.fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
