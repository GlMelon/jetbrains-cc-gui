import { useMemo, useRef, type KeyboardEvent } from 'react';
import hljs from 'highlight.js/lib/core';
import jsonLang from 'highlight.js/lib/languages/json';
import 'highlight.js/styles/github-dark.css';

// 幂等注册 json(hljs 内部去重,与 MarkdownBlock 重复注册无副作用,不依赖加载顺序)。
hljs.registerLanguage('json', jsonLang);

/**
 * 通用 JSON 编辑器:textarea(透明文字 + 光标)+ 同位叠加 hljs 只读高亮层 + 行号 gutter。
 *
 * 编辑态高亮用经典「透明 textarea + 下方 pre 叠加」技巧,需等宽字体 + 锁定 line-height
 * 保证对齐(见 dual-view.less)。parse 失败(error 非 null)时关闭叠加层,退回纯 textarea
 * 显示原文,避免高亮与 textarea 错位。Tab 缩进两空格而非切焦点。
 */
export interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  error?: string | null;
  readOnly?: boolean;
  highlighted?: boolean;
  rows?: number;
  placeholder?: string;
  ariaLabel?: string;
}

export default function JsonEditor({
  value,
  onChange,
  onBlur,
  error = null,
  readOnly = false,
  highlighted = true,
  rows = 8,
  placeholder,
  ariaLabel,
}: JsonEditorProps) {
  const textRef = useRef<HTMLTextAreaElement>(null);
  const gutterRef = useRef<HTMLDivElement>(null);
  const highlightRef = useRef<HTMLPreElement>(null);

  const showHighlight = highlighted && !error;

  const html = useMemo(() => {
    if (!showHighlight) return '';
    try {
      // 末尾补 \n 让高亮 pre 末行与 textarea 等高对齐。
      return hljs.highlight(value || '', { language: 'json' }).value + '\n';
    } catch {
      return '';
    }
  }, [value, showHighlight]);

  const lineCount = useMemo(
    () => Math.max((value || '').split('\n').length, 1),
    [value],
  );

  const syncScroll = () => {
    const ta = textRef.current;
    if (!ta) return;
    if (gutterRef.current) gutterRef.current.scrollTop = ta.scrollTop;
    if (highlightRef.current) {
      highlightRef.current.scrollTop = ta.scrollTop;
      highlightRef.current.scrollLeft = ta.scrollLeft;
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Tab') return;
    e.preventDefault();
    const ta = e.currentTarget;
    const start = ta.selectionStart ?? value.length;
    const end = ta.selectionEnd ?? value.length;
    const next = value.slice(0, start) + '  ' + value.slice(end);
    onChange(next);
    // 异步还原光标位置(setState 重渲染 textarea 后)。
    requestAnimationFrame(() => {
      if (textRef.current) {
        textRef.current.selectionStart = textRef.current.selectionEnd = start + 2;
        textRef.current.focus();
      }
    });
  };

  return (
    <div className={`je${error ? ' je--error' : ''}`}>
      <div className="je-editor">
        <div className="je-gutter" ref={gutterRef} aria-hidden="true">
          {Array.from({ length: lineCount }, (_, i) => (
            <div className="je-lineno" key={i}>{i + 1}</div>
          ))}
        </div>
        {showHighlight && (
          <pre className="je-highlight" ref={highlightRef} aria-hidden="true">
            <code
              className="hljs language-json"
              dangerouslySetInnerHTML={{ __html: html }}
            />
          </pre>
        )}
        <textarea
          ref={textRef}
          className="je-textarea"
          value={value}
          rows={rows}
          readOnly={readOnly}
          placeholder={placeholder}
          aria-label={ariaLabel}
          spellCheck={false}
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          onScroll={syncScroll}
          onKeyDown={handleKeyDown}
        />
      </div>
      {error && <div className="je-errormsg" role="alert">{error}</div>}
    </div>
  );
}
