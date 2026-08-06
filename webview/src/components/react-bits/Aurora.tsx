import { useRef, useEffect, useCallback } from 'react';

export interface AuroraProps {
  /** Aurora colors (default: ['#4ea1ff', '#7c3aed', '#ec4899', '#10b981']) */
  colors?: string[];
  /** Animation speed (default: 0.5) */
  speed?: number;
  /** Number of color blobs (default: 3) */
  blobCount?: number;
  /** Blur radius in px (default: 100) */
  blur?: number;
  /** Opacity (default: 0.5) */
  opacity?: number;
  /** Whether to enable the effect (default: true) */
  enabled?: boolean;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * Aurora - A flowing color gradient background effect.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <Aurora />
 *
 * @example
 * <Aurora
 *   colors={['#4ea1ff', '#7c3aed']}
 *   speed={0.3}
 *   opacity={0.6}
 * />
 */
export const Aurora = ({
  colors = ['#4ea1ff', '#7c3aed', '#ec4899', '#10b981'],
  speed = 0.5,
  blobCount = 3,
  blur = 100,
  opacity = 0.5,
  enabled = true,
  className = '',
  style,
}: AuroraProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animationRef = useRef<number>(0);
  const blobsRef = useRef<Array<{ x: number; y: number; vx: number; vy: number; color: string }>>([]);

  const initBlobs = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    blobsRef.current = Array.from({ length: blobCount }, (_, i) => ({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      vx: (Math.random() - 0.5) * speed,
      vy: (Math.random() - 0.5) * speed,
      color: colors[i % colors.length],
    }));
  }, [blobCount, colors, speed]);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    blobsRef.current.forEach((blob) => {
      // Update position
      blob.x += blob.vx;
      blob.y += blob.vy;

      // Bounce off edges
      if (blob.x < 0 || blob.x > canvas.width) blob.vx *= -1;
      if (blob.y < 0 || blob.y > canvas.height) blob.vy *= -1;

      // Draw gradient blob
      const gradient = ctx.createRadialGradient(blob.x, blob.y, 0, blob.x, blob.y, blur);
      gradient.addColorStop(0, blob.color + 'ff');
      gradient.addColorStop(0.5, blob.color + '80');
      gradient.addColorStop(1, blob.color + '00');

      ctx.fillStyle = gradient;
      ctx.fillRect(0, 0, canvas.width, canvas.height);
    });

    animationRef.current = requestAnimationFrame(draw);
  }, [blur]);

  useEffect(() => {
    if (!enabled) return;

    const canvas = canvasRef.current;
    if (!canvas) return;

    const resize = () => {
      canvas.width = canvas.offsetWidth * window.devicePixelRatio;
      canvas.height = canvas.offsetHeight * window.devicePixelRatio;
      initBlobs();
    };

    resize();
    window.addEventListener('resize', resize);
    animationRef.current = requestAnimationFrame(draw);

    return () => {
      window.removeEventListener('resize', resize);
      cancelAnimationFrame(animationRef.current);
    };
  }, [enabled, initBlobs, draw]);

  if (!enabled) return null;

  return (
    <canvas
      ref={canvasRef}
      className={`aurora ${className}`}
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        opacity,
        pointerEvents: 'none',
        ...style,
      }}
    />
  );
};

export default Aurora;
