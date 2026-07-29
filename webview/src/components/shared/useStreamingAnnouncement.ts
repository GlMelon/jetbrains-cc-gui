import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';

export const STREAM_ANNOUNCEMENT_INTERVAL_MS = 1000;

interface UseStreamingAnnouncementOptions {
  latestText: string;
  streamingActive: boolean;
  isUserAtBottomRef?: RefObject<boolean>;
  resetKey: string;
}

function getIncrement(previous: string, current: string): string {
  if (!current) return '';
  if (previous && current.startsWith(previous)) {
    return current.slice(previous.length).trim();
  }
  return current.trim();
}

/**
 * Throttles streaming text for assistive technology without rebuilding the
 * interval for every token. Visual auto-follow suppresses duplicate live
 * announcements while the user remains at the bottom of the conversation.
 */
export function useStreamingAnnouncement({
  latestText,
  streamingActive,
  isUserAtBottomRef,
  resetKey,
}: UseStreamingAnnouncementOptions): string {
  const [announcement, setAnnouncement] = useState('');
  const latestTextRef = useRef(latestText);
  const observedTextRef = useRef('');
  const streamingActiveRef = useRef(streamingActive);
  const wasStreamingRef = useRef(streamingActive);

  useEffect(() => {
    latestTextRef.current = latestText;
    streamingActiveRef.current = streamingActive;
  }, [latestText, streamingActive]);

  useEffect(() => {
    observedTextRef.current = '';
    wasStreamingRef.current = streamingActiveRef.current;
    setAnnouncement('');
  }, [resetKey]);

  useEffect(() => {
    if (!streamingActive) return undefined;

    const announcePendingIncrement = () => {
      const currentText = latestTextRef.current;
      if (!currentText || currentText === observedTextRef.current) return;

      const increment = getIncrement(observedTextRef.current, currentText);
      observedTextRef.current = currentText;
      if (isUserAtBottomRef?.current || !increment) return;

      setAnnouncement(increment);
    };

    const intervalId = window.setInterval(
      announcePendingIncrement,
      STREAM_ANNOUNCEMENT_INTERVAL_MS,
    );
    return () => window.clearInterval(intervalId);
  }, [isUserAtBottomRef, streamingActive]);

  useEffect(() => {
    if (wasStreamingRef.current && !streamingActive) {
      const currentText = latestTextRef.current;
      const finalIncrement = getIncrement(observedTextRef.current, currentText);
      observedTextRef.current = currentText;
      if (finalIncrement) {
        setAnnouncement(finalIncrement);
      }
    }
    wasStreamingRef.current = streamingActive;
  }, [streamingActive]);

  return announcement;
}
