import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CloseIcon } from './Icons';
import { FadeContent } from './react-bits';

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastMessage {
  id: string;
  message: string;
  type?: 'info' | 'success' | 'warning' | 'error';
  action?: ToastAction;
}

interface ToastProps {
  message: ToastMessage;
  onDismiss: (id: string) => void;
  duration?: number;
  animationIndex?: number;
}

/**
 * Toast exit animation duration (ms).
 * Must match --dlg-out in variables.less (0.16s = 160ms).
 * We use 200ms as a safe buffer to ensure CSS animation completes before DOM removal.
 */
const TOAST_EXIT_ANIMATION_MS = 200;

const Toast: React.FC<ToastProps> = ({ message, onDismiss, duration = 1000, animationIndex = 0 }) => {
  const [isExiting, setIsExiting] = useState(false);
  const toastRef = useRef<HTMLDivElement>(null);
  // setTimeout 在浏览器/webview 返回 number;用 ReturnType 推断避免引入 NodeJS 类型
  const exitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Stable dismiss handler - triggers exit animation, then calls onDismiss after animation completes
  const handleDismiss = useCallback(() => {
    if (isExiting) return; // Prevent double dismiss
    setIsExiting(true);
    exitTimerRef.current = setTimeout(() => {
      onDismiss(message.id);
    }, TOAST_EXIT_ANIMATION_MS);
  }, [isExiting, message.id, onDismiss]);

  // Auto-dismiss timer
  useEffect(() => {
    const timer = setTimeout(() => {
      handleDismiss();
    }, duration);

    return () => {
      clearTimeout(timer);
      if (exitTimerRef.current) {
        clearTimeout(exitTimerRef.current);
      }
    };
  }, [message.id, duration, handleDismiss]);

  // Alternative: Use animationend event for more precise timing
  // Uncomment below and remove setTimeout above if you prefer event-driven approach
  /*
  useEffect(() => {
    const el = toastRef.current;
    if (!el || !isExiting) return;

    const handleAnimationEnd = () => {
      onDismiss(message.id);
    };

    el.addEventListener('animationend', handleAnimationEnd, { once: true });
    return () => el.removeEventListener('animationend', handleAnimationEnd);
  }, [isExiting, message.id, onDismiss]);
  */

  return (
    <FadeContent disabled={isExiting} duration={200} delay={animationIndex * 50} offset={8}>
      <div
        ref={toastRef}
        className={`toast toast-${message.type || 'info'} ${isExiting ? 'toast-exit' : ''}`}
      >
        <div className="toast-content">
          <span className="toast-message">{message.message}</span>
          {message.action && (
            <button
              className="toast-action"
              onClick={() => {
                message.action?.onClick();
                handleDismiss();
              }}
            >
              {message.action.label}
            </button>
          )}
          <button
            className="toast-close"
            onClick={handleDismiss}
          >
            <CloseIcon size={16} />
          </button>
        </div>
      </div>
    </FadeContent>
  );
};

interface ToastContainerProps {
  messages: ToastMessage[];
  onDismiss: (id: string) => void;
}

// Toast display duration constants (milliseconds)
const TOAST_DURATION = {
  error: 5000,   // Error messages display for 5 seconds
  warning: 3000, // Warning messages display for 3 seconds
  default: 2000, // Other messages display for 2 seconds
} as const;

// Set different display durations based on message type
const getDuration = (type?: ToastMessage['type']) => {
  switch (type) {
    case 'error':
      return TOAST_DURATION.error;
    case 'warning':
      return TOAST_DURATION.warning;
    default:
      return TOAST_DURATION.default;
  }
};

export const ToastContainer: React.FC<ToastContainerProps> = ({ messages, onDismiss }) => {
  return (
    <div className="toast-container">
      {messages.map((msg, index) => (
        <Toast
          key={msg.id}
          message={msg}
          onDismiss={onDismiss}
          duration={getDuration(msg.type)}
          animationIndex={index}
        />
      ))}
    </div>
  );
};
