import styles from './style.module.less';

interface WaveTextProps {
  text: string;
  /** Animation delay between each character in ms */
  delay?: number;
  /** Additional CSS class */
  className?: string;
}

export const WaveText = ({
  text,
  delay = 100,
  className = '',
}: WaveTextProps) => {
  const chars = text.split('');

  return (
    <p className={`${styles.container} ${className}`}>
      {chars.map((char, index) => (
        <span
          key={index}
          className={styles.waveChar}
          style={{ animationDelay: `${index * delay}ms` }}
        >
          {char === ' ' ? '\u00A0' : char}
        </span>
      ))}
    </p>
  );
};
