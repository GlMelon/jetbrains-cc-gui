import { useState, useCallback, useRef, useEffect } from 'react';
import type { Attachment } from '../components/ChatInputBox/types';

export interface QueuedMessage {
  id: string;
  content: string;
  attachments?: Attachment[];
  queuedAt: number;
}

export interface UseMessageQueueOptions {
  /** Whether AI is currently processing */
  isLoading: boolean;
  /** Callback to execute a message */
  onExecute: (content: string, attachments?: Attachment[]) => void;
}

export interface UseMessageQueueReturn {
  /** Current queue */
  queue: QueuedMessage[];
  /** Add message to queue */
  enqueue: (content: string, attachments?: Attachment[]) => void;
  /** Remove message from queue by id */
  dequeue: (id: string) => void;
  /** Clear entire queue */
  clearQueue: () => void;
  /** Reorder queue by an ordered list of ids (index 0 executes first) */
  reorder: (orderedIds: string[]) => void;
  /** Whether queue has items */
  hasQueuedMessages: boolean;
}

/**
 * Hook for managing message queue
 * Automatically executes next message when loading completes
 */
export function useMessageQueue({
  isLoading,
  onExecute,
}: UseMessageQueueOptions): UseMessageQueueReturn {
  const [queue, setQueue] = useState<QueuedMessage[]>([]);
  const prevLoadingRef = useRef(isLoading);
  const isExecutingFromQueueRef = useRef(false);

  // Generate unique ID
  const generateId = useCallback(() => {
    return `queue-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  // Add message to queue
  const enqueue = useCallback((content: string, attachments?: Attachment[]) => {
    const newItem: QueuedMessage = {
      id: generateId(),
      content,
      attachments,
      queuedAt: Date.now(),
    };
    setQueue(prev => [...prev, newItem]);
  }, [generateId]);

  // Remove message from queue
  const dequeue = useCallback((id: string) => {
    setQueue(prev => prev.filter(item => item.id !== id));
  }, []);

  // Clear entire queue
  const clearQueue = useCallback(() => {
    setQueue([]);
  }, []);

  /**
   * Reorder the queue by the given id sequence (orderedIds[0] executes next).
   * - Ids not present in the current queue are ignored.
   * - Items missing from orderedIds (e.g. enqueued mid-drag) are appended in
   *   their original order so no message is ever dropped.
   */
  const reorder = useCallback((orderedIds: string[]) => {
    setQueue(prev => {
      const byId = new Map(prev.map(item => [item.id, item]));
      const seen = new Set<string>();
      const ordered: QueuedMessage[] = [];
      for (const id of orderedIds) {
        const item = byId.get(id);
        if (item && !seen.has(id)) {
          ordered.push(item);
          seen.add(id);
        }
      }
      const remaining = prev.filter(item => !seen.has(item.id));
      return [...ordered, ...remaining];
    });
  }, []);

  // Auto-execute next message when loading completes
  useEffect(() => {
    // Detect transition from loading to not loading
    const wasLoading = prevLoadingRef.current;
    prevLoadingRef.current = isLoading;

    // If just finished loading and queue has items, execute next
    if (wasLoading && !isLoading && !isExecutingFromQueueRef.current && queue.length > 0) {
      const nextMessage = queue[0];
      isExecutingFromQueueRef.current = true;

      // Remove from queue first
      setQueue(prev => prev.slice(1));

      // Execute with small delay to ensure state updates
      const timer = setTimeout(() => {
        onExecute(nextMessage.content, nextMessage.attachments);
        isExecutingFromQueueRef.current = false;
      }, 50);
      return () => {
        clearTimeout(timer);
        isExecutingFromQueueRef.current = false;
      };
    }
    return undefined;
  }, [isLoading, queue, onExecute]);

  return {
    queue,
    enqueue,
    dequeue,
    clearQueue,
    reorder,
    hasQueuedMessages: queue.length > 0,
  };
}
