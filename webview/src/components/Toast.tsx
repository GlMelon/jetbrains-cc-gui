import React, { useEffect, useState } from 'react';
import { CloseIcon } from './Icons';

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

const Toast: React.FC<ToastProps> = ({ message, onDismiss, duration = 1000, animationIndex = 0 }) => {
  const [isExiting, setIsExiting] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => {
      setIsExiting(true);
      setTimeout(() => onDismiss(message.id), 300); // Wait for exit animation
    }, duration);

    return () => clearTimeout(timer);
  }, [message.id, duration, onDismiss]);

  return (
    <div 
      className={`toast toast-${message.type || 'info'} ${isExiting ? 'toast-exit' : ''}`}
      style={{ '--stagger-delay': `${animationIndex * 50}ms` } as React.CSSProperties}
    >
      <div className="toast-content">
        <span className="toast-message">{message.message}</span>
        {message.action && (
          <button
            className="toast-action"
            onClick={() => {
              message.action?.onClick();
              setIsExiting(true);
              setTimeout(() => onDismiss(message.id), 300);
            }}
          >
            {message.action.label}
          </button>
        )}
        <button
          className="toast-close"
          onClick={() => {
            setIsExiting(true);
            setTimeout(() => onDismiss(message.id), 300);
          }}
        >
          <CloseIcon size={16} />
        </button>
      </div>
    </div>
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

