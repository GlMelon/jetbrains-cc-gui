import { useState, useEffect, useRef, useCallback } from 'react';

type TimeoutId = ReturnType<typeof setTimeout>;

export interface TypewriterTextProps {
  /** Text to type */
  text: string;
  /** Typing speed in ms per character (default: 50) */
  speed?: number;
  /** Deleting speed in ms per character (default: 30) */
  deleteSpeed?: number;
  /** Pause duration before deleting in ms (default: 2000) */
  pauseDuration?: number;
  /** Whether to enable delete cycle (default: false) */
  enableDelete?: boolean;
  /** Custom cursor character (default: '|') */
  cursor?: string;
  /** Whether to show cursor (default: true) */
  showCursor?: boolean;
  /** Cursor blink speed in ms (default: 530) */
  cursorBlinkSpeed?: number;
  /** Whether to start typing immediately (default: true) */
  autoStart?: boolean;
  /** Callback when typing completes */
  onTypeComplete?: () => void;
  /** Callback when delete completes */
  onDeleteComplete?: () => void;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * TypewriterText - A text animation that types and optionally deletes text character by character.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <TypewriterText text="Hello, World!" />
 *
 * @example
 * <TypewriterText
 *   text="Welcome to Claude Code"
 *   speed={100}
 *   enableDelete
 *   pauseDuration={3000}
 * />
 */
export const TypewriterText = ({
  text,
  speed = 50,
  deleteSpeed = 30,
  pauseDuration = 2000,
  enableDelete = false,
  cursor = '|',
  showCursor = true,
  cursorBlinkSpeed = 530,
  autoStart = true,
  onTypeComplete,
  onDeleteComplete,
  className = '',
  style,
}: TypewriterTextProps) => {
  const [displayedText, setDisplayedText] = useState('');
  const indexRef = useRef(0);
  const timeoutRef = useRef<TimeoutId | null>(null);
  const phaseRef = useRef<'typing' | 'pausing' | 'deleting'>('typing');

  const clearTimer = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, []);

  const typeNextChar = useCallback(() => {
    if (indexRef.current < text.length) {
      setDisplayedText(text.slice(0, indexRef.current + 1));
      indexRef.current += 1;
      timeoutRef.current = setTimeout(typeNextChar, speed);
    } else {
      onTypeComplete?.();
      if (enableDelete) {
        phaseRef.current = 'pausing';
        timeoutRef.current = setTimeout(() => {
          phaseRef.current = 'deleting';
          deleteNextChar();
        }, pauseDuration);
      }
    }
  }, [text, speed, enableDelete, pauseDuration, onTypeComplete]);

  const deleteNextChar = useCallback(() => {
    if (indexRef.current > 0) {
      indexRef.current -= 1;
      setDisplayedText(text.slice(0, indexRef.current));
      timeoutRef.current = setTimeout(deleteNextChar, deleteSpeed);
    } else {
      onDeleteComplete?.();
      if (enableDelete) {
        phaseRef.current = 'typing';
        timeoutRef.current = setTimeout(typeNextChar, pauseDuration);
      }
    }
  }, [text, deleteSpeed, enableDelete, pauseDuration, onDeleteComplete, typeNextChar]);

  const startTyping = useCallback(() => {
    clearTimer();
    indexRef.current = 0;
    setDisplayedText('');
    phaseRef.current = 'typing';
    timeoutRef.current = setTimeout(typeNextChar, speed);
  }, [clearTimer, typeNextChar, speed]);

  useEffect(() => {
    if (autoStart) {
      startTyping();
    }
    return clearTimer;
  }, [text, autoStart]);

  return (
    <span
      className={`typewriter-text ${className}`}
      style={{
        display: 'inline-block',
        ...style,
      }}
    >
      {displayedText}
      {showCursor && (
        <span
          className="typewriter-cursor"
          style={{
            animation: `cursor-blink ${cursorBlinkSpeed}ms infinite`,
            marginLeft: '1px',
          }}
        >
          {cursor}
        </span>
      )}
      <style>{`
        @keyframes cursor-blink {
          0%, 50% { opacity: 1; }
          51%, 100% { opacity: 0; }
        }
      `}</style>
    </span>
  );
};

export default TypewriterText;
