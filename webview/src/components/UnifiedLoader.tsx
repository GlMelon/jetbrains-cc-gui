import { PulseLoader, WaveLoader, BounceLoader, SpinLoader, OrbitLoader } from './react-bits';

export type LoaderType = 'pulse' | 'wave' | 'bounce' | 'spin' | 'orbit';

interface UnifiedLoaderProps {
  type?: LoaderType;
  active?: boolean;
  size?: number;
  color?: string;
  className?: string;
}

/**
 * 统一的 Loading 组件
 * 替代所有 codicon-loading 旋转图标
 */
export const UnifiedLoader = ({
  type = 'pulse',
  active = true,
  size = 16,
  color = 'var(--accent-primary, #4ea1ff)',
  className = '',
}: UnifiedLoaderProps) => {
  const loaderClass = `unified-loader ${className}`;

  switch (type) {
    case 'wave':
      return <WaveLoader active={active} height={size} color={color} className={loaderClass} />;
    case 'bounce':
      return <BounceLoader active={active} size={size} color={color} className={loaderClass} />;
    case 'spin':
      return <SpinLoader active={active} size={size} color={color} className={loaderClass} />;
    case 'orbit':
      return <OrbitLoader active={active} size={size} color={color} className={loaderClass} />;
    case 'pulse':
    default:
      return <PulseLoader active={active} size={size} color={color} className={loaderClass} />;
  }
};

export default UnifiedLoader;