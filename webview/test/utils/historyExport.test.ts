import { describe, expect, it } from 'vitest';
import { HISTORY_EXPORT_FORMAT } from '../../src/generated/protocol';
import { parseHistoryExportPayload } from '../../src/utils/historyExport';

describe('parseHistoryExportPayload', () => {
  it('accepts a complete bounded JSON export payload', () => {
    const raw = JSON.stringify({
      success: true,
      sessionId: 'session-1',
      title: 'Demo',
      format: HISTORY_EXPORT_FORMAT.JSON,
      fileName: 'Demo_session-.json',
      mimeType: 'application/json;charset=utf-8',
      content: '{"messages":[{"type":"user"}]}',
      truncated: true,
      exportedMessageCount: 1,
      omittedMessageCount: 2,
      maxMessageCount: 10,
      maxUtf8Bytes: 1024,
    });

    const parsed = parseHistoryExportPayload(raw);

    expect(parsed?.success).toBe(true);
    if (parsed?.success) {
      expect(parsed.format).toBe(HISTORY_EXPORT_FORMAT.JSON);
      expect(parsed.content).toContain('"messages"');
      expect(parsed.omittedMessageCount).toBe(2);
    }
  });

  it('accepts backend-rendered HTML without interpreting its content', () => {
    const content = '<!doctype html><title>Safe export</title>';
    const parsed = parseHistoryExportPayload({
      success: true,
      sessionId: 'session-2',
      title: 'Safe export',
      format: HISTORY_EXPORT_FORMAT.HTML,
      fileName: 'safe-export.html',
      mimeType: 'text/html;charset=utf-8',
      content,
      truncated: false,
      exportedMessageCount: 0,
      omittedMessageCount: 0,
      maxMessageCount: 10_000,
      maxUtf8Bytes: 8 * 1024 * 1024,
    });

    expect(parsed?.success).toBe(true);
    if (parsed?.success) {
      expect(parsed.format).toBe(HISTORY_EXPORT_FORMAT.HTML);
      expect(parsed.content).toBe(content);
    }
  });

  it('accepts backend error payloads', () => {
    expect(parseHistoryExportPayload({ success: false, error: 'failed' })).toEqual({
      success: false,
      error: 'failed',
    });
  });

  it('rejects malformed or incomplete success payloads', () => {
    const base = {
      success: true,
      sessionId: 'session-1',
      title: 'Demo',
      format: HISTORY_EXPORT_FORMAT.JSON,
      fileName: 'demo.json',
      mimeType: 'application/json;charset=utf-8',
      content: '{}',
      truncated: false,
      exportedMessageCount: 1,
      omittedMessageCount: 0,
      maxMessageCount: 10,
      maxUtf8Bytes: 1024,
    };

    expect(parseHistoryExportPayload('{bad')).toBeNull();
    expect(parseHistoryExportPayload({ success: 'true' })).toBeNull();
    expect(parseHistoryExportPayload({ ...base, format: 'pdf' })).toBeNull();
    expect(parseHistoryExportPayload({ ...base, mimeType: undefined })).toBeNull();
    expect(parseHistoryExportPayload({ ...base, content: undefined })).toBeNull();
    expect(parseHistoryExportPayload({ ...base, content: { messages: [] } })).toBeNull();
    expect(parseHistoryExportPayload({ ...base, exportedMessageCount: -1 })).toBeNull();
  });
});
