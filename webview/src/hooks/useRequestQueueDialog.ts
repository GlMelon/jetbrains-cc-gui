import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 请求队列 Dialog 的泛型 hook。
 *
 * 封装了：
 * - isOpen / currentRequest 状态
 * - ref 镜像（避免闭包问题）
 * - 待处理请求队列
 * - open 函数（自动入队）
 * - close 函数（自动出队）
 * - 消费队列的 useEffect
 */
export function useRequestQueueDialog<TRequest>({
  getId,
}: {
  /** 从请求中提取唯一 ID */
  getId: (request: TRequest) => string;
}) {
  // Dialog 状态
  const [isOpen, setIsOpen] = useState(false);
  const [currentRequest, setCurrentRequest] = useState<TRequest | null>(null);

  // Ref 镜像（避免闭包中的 stale 值）
  const isOpenRef = useRef(false);
  const currentRequestRef = useRef<TRequest | null>(null);
  const pendingRequestsRef = useRef<TRequest[]>([]);

  // 同步 ref 与 state
  useEffect(() => {
    isOpenRef.current = isOpen;
    currentRequestRef.current = currentRequest;
  }, [isOpen, currentRequest]);

  // 打开 Dialog（如果已有请求则入队）
  const open = useCallback(
    (request: TRequest) => {
      // 如果 Dialog 已打开或有当前请求，入队
      if (isOpenRef.current || currentRequestRef.current) {
        const currentId = currentRequestRef.current ? getId(currentRequestRef.current) : null;
        const requestId = getId(request);
        const alreadyQueued = pendingRequestsRef.current.some(
          (item) => getId(item) === requestId
        );
        if (requestId !== currentId && !alreadyQueued) {
          pendingRequestsRef.current.push(request);
        }
        return;
      }

      // 直接打开
      currentRequestRef.current = request;
      isOpenRef.current = true;
      setCurrentRequest(request);
      setIsOpen(true);
    },
    [getId]
  );

  // 关闭 Dialog 并消费下一个待处理请求
  const close = useCallback(() => {
    isOpenRef.current = false;
    currentRequestRef.current = null;
    setIsOpen(false);
    setCurrentRequest(null);
  }, []);

  // 消费待处理请求队列
  useEffect(() => {
    if (isOpen || currentRequest) return;
    const next = pendingRequestsRef.current.shift();
    if (next) {
      open(next);
    }
  }, [isOpen, currentRequest, open]);

  return {
    isOpen,
    currentRequest,
    open,
    close,
  };
}
