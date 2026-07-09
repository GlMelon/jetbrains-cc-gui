/**
 * OpenCode provider icon component.
 * Custom SVG icon for the OpenCode AI provider.
 */
import type { ReactElement } from 'react';

interface OpenCodeIconProps {
  size?: number;
  colored?: boolean;
}

/**
 * OpenCode logo - a stylized monitor/screen shape with inner rectangle.
 * Source: cc-switch-inspect project opencode-logo-light.svg
 */
export const OpenCodeIcon = ({ size = 24, colored = false }: OpenCodeIconProps): ReactElement => {
  const innerFill = colored ? '#CFCECD' : 'currentColor';
  const outerFill = colored ? '#211E1E' : 'currentColor';

  return (
    <svg
      height={size}
      width={size}
      viewBox="0 0 240 300"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="OpenCode"
      role="img"
    >
      <g clipPath="url(#opencode-clip)">
        <mask
          id="opencode-mask"
          style={{ maskType: 'luminance' }}
          maskUnits="userSpaceOnUse"
          x="0"
          y="0"
          width="240"
          height="300"
        >
          <path d="M240 0H0V300H240V0Z" fill="white" />
        </mask>
        <g mask="url(#opencode-mask)">
          <path d="M180 240H60V120H180V240Z" fill={innerFill} opacity={colored ? undefined : 0.42} />
          <path d="M180 60H60V240H180V60ZM240 300H0V0H240V300Z" fill={outerFill} />
        </g>
      </g>
      <defs>
        <clipPath id="opencode-clip">
          <rect width="240" height="300" fill="white" />
        </clipPath>
      </defs>
    </svg>
  );
};
