import { useEffect, useMemo, useRef, useState } from 'react';

interface UseLiveElapsedMsOptions {
  /** Backend-provided elapsed snapshot, in milliseconds. */
  elapsedMs?: number;
  /** Timestamp when the current generation/loading cycle started. */
  startedAt?: number | null;
  /** Whether the timer should continue ticking. */
  active?: boolean;
}

const normalizeElapsedMs = (elapsedMs?: number): number | null => {
  if (typeof elapsedMs !== 'number' || !Number.isFinite(elapsedMs) || elapsedMs < 0) {
    return null;
  }
  return elapsedMs;
};

const normalizeStartedAt = (startedAt?: number | null): number | null => {
  if (typeof startedAt !== 'number' || !Number.isFinite(startedAt) || startedAt <= 0) {
    return null;
  }
  return startedAt;
};

export function formatLiveElapsedMs(elapsedMs: number | null): string | null {
  if (elapsedMs === null || !Number.isFinite(elapsedMs) || elapsedMs < 0) {
    return null;
  }

  const totalSeconds = Math.floor(elapsedMs / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds}s`;
  }

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds.toString().padStart(2, '0')}s`;
}

export function useLiveElapsedMs({ elapsedMs, startedAt, active = true }: UseLiveElapsedMsOptions): number | null {
  const normalizedElapsedMs = normalizeElapsedMs(elapsedMs);
  const normalizedStartedAt = normalizeStartedAt(startedAt);
  const baselineRef = useRef({
    elapsedMs: normalizedElapsedMs,
    startedAt: normalizedStartedAt,
    capturedAt: Date.now(),
  });

  const computeElapsedMs = useMemo(() => () => {
    const baseline = baselineRef.current;
    const now = Date.now();

    if (baseline.elapsedMs !== null) {
      return baseline.elapsedMs + (active ? Math.max(0, now - baseline.capturedAt) : 0);
    }

    if (baseline.startedAt !== null) {
      return Math.max(0, now - baseline.startedAt);
    }

    return null;
  }, [active]);

  const [liveElapsedMs, setLiveElapsedMs] = useState<number | null>(() => {
    if (normalizedElapsedMs !== null) return normalizedElapsedMs;
    if (normalizedStartedAt !== null) return Math.max(0, Date.now() - normalizedStartedAt);
    return null;
  });

  useEffect(() => {
    baselineRef.current = {
      elapsedMs: normalizedElapsedMs,
      startedAt: normalizedStartedAt,
      capturedAt: Date.now(),
    };
    setLiveElapsedMs(computeElapsedMs());

    if (!active) {
      return undefined;
    }

    const intervalId = window.setInterval(() => {
      setLiveElapsedMs(computeElapsedMs());
    }, 1000);

    return () => window.clearInterval(intervalId);
  }, [active, computeElapsedMs, normalizedElapsedMs, normalizedStartedAt]);

  return liveElapsedMs;
}
