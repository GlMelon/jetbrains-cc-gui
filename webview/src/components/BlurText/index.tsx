import { useEffect, useState, useRef } from 'react';
import styles from './style.module.less';

interface BlurTextProps {
  text: string;
  /** Animation delay between each character in ms */
  delay?: number;
  /** Animation direction: 'top' or 'bottom' */
  direction?: 'top' | 'bottom';
  /** Split by 'chars' or 'words' */
  animateBy?: 'chars' | 'words';
  /** Additional CSS class */
  className?: string;
}

export const BlurText = ({
  text,
  delay = 100,
  direction = 'top',
  animateBy = 'chars',
  className = '',
}: BlurTextProps) => {
  const [inView, setInView] = useState(false);
  const ref = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          observer.unobserve(ref.current!);
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);

  const elements = animateBy === 'words' ? text.split(' ') : text.split('');

  return (
    <p ref={ref} className={`${styles.container} ${className}`}>
      {elements.map((segment, index) => (
        <span
          key={index}
          className={`${styles.segment} ${
            inView
              ? direction === 'top'
                ? styles.blurInTop
                : styles.blurInBottom
              : styles.hidden
          }`}
          style={{ animationDelay: `${index * delay}ms` }}
        >
          {segment === ' ' ? '\u00A0' : segment}
          {animateBy === 'words' && index < elements.length - 1 && '\u00A0'}
        </span>
      ))}
    </p>
  );
};
