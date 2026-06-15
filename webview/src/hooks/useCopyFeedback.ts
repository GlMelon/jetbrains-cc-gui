import { useState, useCallback, useRef, useEffect } from 'react';

/**
 * 复制按钮状态 hook。
 *
 * 统一处理"点击复制 → 显示 ✓ → N 秒后复位"的逻辑。
 *
 * @param resetDelay - 复位延迟时间（毫秒），默认 2000
 * @returns { copiedId, handleCopy, reset }
 *
 * @example
 * ```tsx
 * const { copiedId, handleCopy } = useCopyFeedback();
 *
 * <button onClick={() => handleCopy('message-123', () => navigator.clipboard.writeText(text))}>
 *   {copiedId === 'message-123' ? '✓ 已复制' : '复制'}
 * </button>
 * ```
 */
export function useCopyFeedback(resetDelay = 2000) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 清理 timeout
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  const handleCopy = useCallback(
    async (id: string, copyFn: () => void | Promise<void>) => {
      try {
        await copyFn();
        setCopiedId(id);

        // 清理之前的 timeout
        if (timeoutRef.current) {
          clearTimeout(timeoutRef.current);
        }

        // 设置新的 timeout
        timeoutRef.current = setTimeout(() => {
          setCopiedId(null);
          timeoutRef.current = null;
        }, resetDelay);
      } catch (error) {
        console.error('[useCopyFeedback] Copy failed:', error);
      }
    },
    [resetDelay]
  );

  const reset = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    setCopiedId(null);
  }, []);

  return { copiedId, handleCopy, reset };
}
