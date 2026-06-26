import React, { useState, useRef, useEffect, useCallback } from 'react';
import { ChevronDownIcon } from './Icons';

interface CollapsibleTextBlockProps {
  content: string;
}

const MAX_HEIGHT = 160; // Approx 7-8 lines

const CollapsibleTextBlock: React.FC<CollapsibleTextBlockProps> = ({ content }) => {
  const [expanded, setExpanded] = useState(false);
  const [isOverflowing, setIsOverflowing] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);

  const checkHeight = useCallback(() => {
    if (contentRef.current) {
      setIsOverflowing(contentRef.current.scrollHeight > MAX_HEIGHT);
    }
  }, []);

  // Create the ResizeObserver once; tearing it down on every content change
  // (the previous implementation) caused observer churn and forced layout
  // reads whenever the parent re-rendered with a new content reference.
  useEffect(() => {
    const el = contentRef.current;
    if (!el) return;
    const observer = new ResizeObserver(checkHeight);
    observer.observe(el);
    return () => observer.disconnect();
  }, [checkHeight]);

  // Re-measure immediately when content changes (the observer also fires, but
  // asynchronously — this keeps the toggle button in sync without recreating
  // the observer).
  useEffect(() => {
    checkHeight();
  }, [content, checkHeight]);

  const toggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setExpanded(!expanded);
  };

  const contentStyle: React.CSSProperties = {
    maxHeight: (expanded || !isOverflowing) ? 'none' : `${MAX_HEIGHT}px`,
    overflow: 'hidden',
  };
  const chevronStyle: React.CSSProperties = {
    transform: expanded ? 'rotate(180deg)' : 'none',
    transition: 'transform 0.2s',
  };

  return (
    <div className={`collapsible-block ${expanded ? 'expanded' : 'collapsed'}`}>
      <div
        className="collapsible-content"
        ref={contentRef}
        style={contentStyle}
      >
        <div className="plain-text-content">{content}</div>

        {/* Gradient overlay when collapsed */}
        {!expanded && isOverflowing && (
             <div className="collapse-overlay"></div>
        )}
      </div>

      {isOverflowing && (
        <div className="collapse-toggle" onClick={toggleExpand}>
            <ChevronDownIcon size={16} style={chevronStyle} />
        </div>
      )}
    </div>
  );
};

export default CollapsibleTextBlock;
