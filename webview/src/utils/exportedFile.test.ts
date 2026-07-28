import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sendAction } from '../bridge/typed';
import { HISTORY_EXPORT_FORMAT, UPSTREAM } from '../generated/protocol';
import type { SuccessfulHistoryExportPayload } from './historyExport';
import { downloadExportedFile } from './exportedFile';

vi.mock('../bridge/typed', () => ({
  sendAction: vi.fn(),
}));

const payload: SuccessfulHistoryExportPayload = {
  success: true,
  sessionId: 'session-1',
  title: 'Demo',
  format: HISTORY_EXPORT_FORMAT.HTML,
  fileName: 'demo.html',
  mimeType: 'text/html;charset=utf-8',
  content: '<!doctype html><title>Demo</title>',
  truncated: false,
  exportedMessageCount: 1,
  omittedMessageCount: 0,
  maxMessageCount: 10_000,
  maxUtf8Bytes: 8 * 1024 * 1024,
};

describe('downloadExportedFile', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('uses the typed save action with exact backend-rendered fields', () => {
    vi.mocked(sendAction).mockReturnValue(true);

    downloadExportedFile(payload);

    expect(sendAction).toHaveBeenCalledWith(
      UPSTREAM.SAVE_EXPORTED_FILE,
      JSON.stringify({
        content: payload.content,
        fileName: payload.fileName,
        format: payload.format,
      }),
    );
  });

  it('falls back to a browser download without transforming content', () => {
    vi.mocked(sendAction).mockReturnValue(false);
    // 标注 Blob 参数类型,让 mock.calls[0][0] 推断为 Blob(否则空元组报错)
    const createObjectURL = vi.fn((_blob: Blob) => 'blob:history-export');
    const revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });

    downloadExportedFile(payload);

    expect(createObjectURL).toHaveBeenCalledTimes(1);
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    expect(blob.type).toBe(payload.mimeType);
    expect(click).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:history-export');

    click.mockRestore();
    vi.unstubAllGlobals();
  });
});
