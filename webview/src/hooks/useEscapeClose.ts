import { useEffect } from 'react';

/**
 * 注册 ESC 键关闭逻辑。
 * 当 isOpen 为 true 时，按 ESC 调用 onClose。
 *
 * @param isOpen - 弹窗是否打开
 * @param onClose - 关闭回调
 */
export function useEscapeClose(isOpen: boolean, onClose: () => void): void {
  useEffect(() => {
    if (isOpen) {
      const handleEscape = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
          onClose();
        }
      };
      window.addEventListener('keydown', handleEscape);
      return () => window.removeEventListener('keydown', handleEscape);
    }
  }, [isOpen]); // onClose 稳定，不加入依赖
}
