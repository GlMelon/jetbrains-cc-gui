import { HISTORY_EXPORT_FORMAT } from '../generated/protocol';
import type { HistoryExportFormat, HistoryExportPayloadWire } from '../generated/protocol';

export interface SuccessfulHistoryExportPayload extends HistoryExportPayloadWire {
  success: true;
  sessionId: string;
  title: string;
  format: HistoryExportFormat;
  fileName: string;
  mimeType: string;
  content: string;
  truncated: boolean;
  exportedMessageCount: number;
  omittedMessageCount: number;
  maxMessageCount: number;
  maxUtf8Bytes: number;
}

export interface FailedHistoryExportPayload extends HistoryExportPayloadWire {
  success: false;
  error?: string;
}

export type HistoryExportPayload = SuccessfulHistoryExportPayload | FailedHistoryExportPayload;

/** Format-only validation for the backend-owned history export payload. */
export function parseHistoryExportPayload(raw: unknown): HistoryExportPayload | null {
  let value: unknown = raw;
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!isRecord(value) || typeof value.success !== 'boolean') {
    return null;
  }
  if (!value.success) {
    return typeof value.error === 'undefined' || typeof value.error === 'string'
      ? (value as unknown as FailedHistoryExportPayload)
      : null;
  }
  if (
    typeof value.sessionId !== 'string' ||
    typeof value.title !== 'string' ||
    !isHistoryExportFormat(value.format) ||
    typeof value.fileName !== 'string' ||
    typeof value.mimeType !== 'string' ||
    typeof value.content !== 'string' ||
    typeof value.truncated !== 'boolean' ||
    !isNonNegativeInteger(value.exportedMessageCount) ||
    !isNonNegativeInteger(value.omittedMessageCount) ||
    !isNonNegativeInteger(value.maxMessageCount) ||
    !isNonNegativeInteger(value.maxUtf8Bytes)
  ) {
    return null;
  }
  return value as unknown as SuccessfulHistoryExportPayload;
}

function isHistoryExportFormat(value: unknown): value is HistoryExportFormat {
  return typeof value === 'string'
    && (Object.values(HISTORY_EXPORT_FORMAT) as string[]).includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}
