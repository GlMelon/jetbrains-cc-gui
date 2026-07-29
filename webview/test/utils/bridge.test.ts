import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  openBrowser,
  openClass,
  openFile,
  resolveFilePathWithCallback,
  showEditableDiff,
  showInteractiveDiff,
  undoFileChanges,
} from '../../src/utils/bridge';
import { sendAction } from '../../src/bridge/typed';
import { UPSTREAM } from '../../src/generated/protocol';

describe('bridge navigation helpers', () => {
  const bridgeCall = (type: string, content = '') =>
    JSON.stringify({ type, content });

  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  it('decodes percent-encoded navigation paths for openFile', () => {
    openFile('/Users/demo/my%20file.ts');
    openFile('/Users/demo/%C3%BCber.txt');
    openFile('file:///Users/demo/my%20file.ts');

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('open_file', '/Users/demo/my file.ts'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, bridgeCall('open_file', '/Users/demo/über.txt'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(3, bridgeCall('open_file', '/Users/demo/my file.ts'));
  });

  it('parses line numbers from normalized navigation paths', () => {
    openFile('/Users/demo/my%20file.ts:42');

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('open_file', '/Users/demo/my file.ts:42'));
  });

  it('allows relative navigation paths for openFile', () => {
    openFile('../shared/utils.ts');
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('open_file', '../shared/utils.ts'));
  });

  it('sends openClass for valid Java FQCN values', () => {
    openClass('com.github.claudecodegui.handler.file.OpenFileHandler');
    expect(window.sendToJava).toHaveBeenCalledWith(
      bridgeCall('open_class', 'com.github.claudecodegui.handler.file.OpenFileHandler'),
    );
  });

  it('shares the same trimmed FQCN validation rules as linkify', () => {
    openClass(' com.github.foo.BarService ');
    openClass('com.github.foo.Outer.Inner');
    openClass('org.junit.jupiter.api');
    openClass('com.github.foo.Bar.baz()');

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('open_class', 'com.github.foo.BarService'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, bridgeCall('open_class', 'com.github.foo.Outer.Inner'));
    expect(window.sendToJava).toHaveBeenCalledTimes(2);
  });

  it('rejects invalid Java class expressions', () => {
    openClass('com.github.foo.Bar#baz');
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('keeps traversal guards for mutating file APIs', () => {
    showEditableDiff('../shared/utils.ts', [], 'M');
    showInteractiveDiff('../shared/utils.ts', 'next');
    undoFileChanges('../shared/utils.ts', 'M', []);

    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('allows http, https, and mailto protocols for openBrowser', () => {
    openBrowser('https://example.com/docs');
    openBrowser('http://example.com');
    openBrowser('mailto:test@example.com');

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('open_browser', 'https://example.com/docs'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, bridgeCall('open_browser', 'http://example.com'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(3, bridgeCall('open_browser', 'mailto:test@example.com'));
  });

  it('rejects file: and javascript: protocols in openBrowser', () => {
    openBrowser('file:///etc/passwd');
    openBrowser('javascript:alert(1)');

    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('rejects control-character-obfuscated navigation targets', () => {
    openFile('java\nscript:alert(1)');
    openClass('com.github.foo.Bar\u0000Baz');
    showEditableDiff('..\u0000/shared/utils.ts', [], 'M');

    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('encodes bridge actions as structured JSON to preserve special characters', () => {
    sendAction(UPSTREAM.OPEN_FILE, 'Checking SDK status|get_dependency_status\n---main.tsx---(foo');

    expect(window.sendToJava).toHaveBeenCalledWith(
      JSON.stringify({
        type: UPSTREAM.OPEN_FILE,
        content: 'Checking SDK status|get_dependency_status\n---main.tsx---(foo',
      }),
    );
  });
});

describe('resolveFilePathWithCallback', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('requests resolution via the upstream resolve_file_path action the backend actually routes', () => {
    // 后端 ResolveFilePathActionHandler 注册的 action 是 UpstreamAction.RESOLVE_FILE_PATH
    // (= 'resolve_file_path')。前端 RPC 的请求 type 必须是同名上行 action —— 否则后端
    // dispatcher miss,回包永不到达,前端 5s 超时回 null,文件链接悬停解析静默失效。
    resolveFilePathWithCallback('src/App.tsx', () => {});

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const raw = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    const envelope = JSON.parse(raw);
    const payload = JSON.parse(envelope.content);

    expect(envelope.type).toBe(UPSTREAM.RESOLVE_FILE_PATH);
    expect(payload.path).toBe('src/App.tsx');
    expect(payload.__requestId).toBeTruthy();
  });
});
