/**
 * Unified SVG Icon Components
 * Replaces Codicon font icons with inline SVG for better control and consistency
 *
 * Style: stroke-width: 1.8, stroke-linecap: round, stroke-linejoin: round
 */

import React from 'react';

/** Size tokens — preferred values for icon sizing */
export const ICON_SM = 14 as const;
export const ICON_MD = 16 as const;
export const ICON_LG = 20 as const;
export type IconSize = typeof ICON_SM | typeof ICON_MD | typeof ICON_LG;

interface IconProps {
  size?: IconSize;
  className?: string;
  style?: React.CSSProperties;
}

// Helper to create icon component with consistent styling
const createIcon = (path: React.ReactNode, viewBox = '0 0 24 24') => {
  const Icon: React.FC<IconProps> = ({ size = 16, className, style }) => (
    <svg
      width={size}
      height={size}
      viewBox={viewBox}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={style}
    >
      {path}
    </svg>
  );
  Icon.displayName = 'Icon';
  return Icon;
};

// ==================== Navigation & Actions ====================

export const BackIcon = createIcon(
  <>
    <path d="M19 12H5" />
    <path d="M12 19l-7-7 7-7" />
  </>
);

export const ForwardIcon = createIcon(
  <>
    <path d="M5 12h14" />
    <path d="M12 5l7 7-7 7" />
  </>
);

export const CloseIcon = createIcon(
  <>
    <path d="M18 6L6 18" />
    <path d="M6 6l12 12" />
  </>
);

export const CheckIcon = createIcon(<polyline points="20 6 9 17 4 12" />);

export const ChevronDownIcon = createIcon(<path d="M6 9l6 6 6-6" />);

export const ChevronUpIcon = createIcon(<path d="M18 15l-6-6-6 6" />);

export const ChevronLeftIcon = createIcon(<path d="M15 18l-6-6 6-6" />);

export const ChevronRightIcon = createIcon(<path d="M9 18l6-6-6-6" />);

export const ArrowUpIcon = createIcon(
  <>
    <path d="M12 19V5" />
    <path d="M5 12l7-7 7 7" />
  </>
);

export const ArrowDownIcon = createIcon(
  <>
    <path d="M12 5v14" />
    <path d="M19 12l-7 7-7-7" />
  </>
);

export const ArrowRightIcon = createIcon(
  <>
    <path d="M5 12h14" />
    <path d="M12 5l7 7-7 7" />
  </>
);

// ==================== Communication ====================

export const SendIcon = createIcon(
  <>
    <path d="M22 2L11 13" />
    <path d="M22 2l-7 20-4-9-9-4 20-7z" />
  </>
);

export const StopIcon = createIcon(<rect x="6" y="6" width="12" height="12" rx="2" />);

export const MessageIcon = createIcon(<path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />);

export const ChatIcon = createIcon(
  <>
    <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z" />
  </>
);

// Compass / 罗盘 — 方案一 Plan 模式图标(Claude 官方 compass 语义)
export const CompassIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="9" />
    <polygon points="12 4.5 14.2 11 12 12.5 9.8 11" fill="currentColor" stroke="none" />
    <polygon points="12 19.5 9.8 13 12 11.5 14.2 13" fill="currentColor" stroke="none" opacity="0.45" />
    <circle cx="12" cy="12" r="0.9" fill="currentColor" stroke="none" />
  </>
);

// ReasoningGauge / 油表仪表盘 — 推理程度图标。指针角度随级别从左(low)扫到右(max),
// 底部半圆为表盘(opacity 0.4 中性灰);指针+中心轴点按级别彩色(绿→红,强度递增),直观反映思考深度。
// 注:本文件另有固定 GaugeIcon(配额/用量),此为参数化版,故独立命名。
const REASONING_GAUGE_NEEDLE_TIP: ReadonlyArray<readonly [number, number]> = [
  [6.34, 10.34], // 0 low  (左上)
  [8.94, 8.61], // 1 medium
  [12, 8], // 2 high (正上)
  [15.06, 8.61], // 3 xhigh
  [17.66, 10.34], // 4 max  (右上)
];

// 档位颜色:绿(low,温和/省)→青柠→琥珀→橙→红(max,极致/烧),强度递增;深色背景下区分清晰。
// 表盘(path)保持中性灰(opacity 0.4),仅指针+中心点着色,聚焦"当前强度"一眼可辨。
const REASONING_GAUGE_COLORS: ReadonlyArray<string> = [
  '#4caf50', // 0 low  绿
  '#cddc39', // 1 medium 青柠
  '#ffc107', // 2 high 琥珀
  '#ff9800', // 3 xhigh 橙
  '#ef5350', // 4 max  红
];

interface ReasoningGaugeIconProps extends IconProps {
  /** 档位 0-4(low→max),决定指针角度;越界回退 low */
  level?: number;
}

export const ReasoningGaugeIcon: React.FC<ReasoningGaugeIconProps> = ({ level = 0, size = ICON_MD, className, style }) => {
  const tip = REASONING_GAUGE_NEEDLE_TIP[level] ?? REASONING_GAUGE_NEEDLE_TIP[0];
  const color = REASONING_GAUGE_COLORS[level] ?? REASONING_GAUGE_COLORS[0];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={style}
    >
      <path d="M4 16 A8 8 0 0 1 20 16" opacity="0.4" />
      <line x1="12" y1="16" x2={tip[0]} y2={tip[1]} stroke={color} />
      <circle cx="12" cy="16" r="1.4" fill={color} stroke="none" />
    </svg>
  );
};
ReasoningGaugeIcon.displayName = 'ReasoningGaugeIcon';

// ==================== Files & Folders ====================

export const FileIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
  </>
);

export const FileCodeIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
    <path d="M10 13l-2 2 2 2" />
    <path d="M14 13l2 2-2 2" />
  </>
);

export const FileTextIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
    <path d="M16 13H8" />
    <path d="M16 17H8" />
    <path d="M10 9H8" />
  </>
);

export const FolderIcon = createIcon(<path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" />);

export const SaveIcon = createIcon(
  <>
    <path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z" />
    <polyline points="17 21 17 13 7 13 7 21" />
    <polyline points="7 3 7 8 15 8" />
  </>
);

// ==================== Edit & Tools ====================

export const EditIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
    <path d="M8 19l-1-4 8.5-8.5a1.8 1.8 0 012.5 0 1.8 1.8 0 010 2.5L9.5 17.5z" />
    <path d="M7 15l4 1" />
  </>
);

export const TrashIcon = createIcon(
  <>
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
    <line x1="10" y1="11" x2="10" y2="17" />
    <line x1="14" y1="11" x2="14" y2="17" />
  </>
);

export const CopyIcon = createIcon(
  <>
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
  </>
);

export const PasteIcon = createIcon(
  <>
    <path d="M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2" />
    <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
  </>
);

export const UndoIcon = createIcon(
  <>
    <path d="M3 7v6h6" />
    <path d="M21 17a9 9 0 00-9-9 9 9 0 00-6 2.3L3 13" />
  </>
);

// Counter-clockwise rotate icon - for rewind button
export const RotateCounterClockwiseIcon = createIcon(
  <>
    <path d="M3 7v6h6" />
    <path d="M21 17a9 9 0 00-9-9 9 9 0 00-6.69 3L3 13" />
    <polyline points="3 7 3 13 9 13" />
  </>
);

export const RedoIcon = createIcon(
  <>
    <path d="M21 7v6h-6" />
    <path d="M3 17a9 9 0 019-9 9 9 0 016 2.3L21 13" />
  </>
);

export const RefreshIcon = createIcon(
  <>
    <polyline points="23 4 23 10 17 10" />
    <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10" />
  </>
);

export const AttachIcon = createIcon(
  <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
);

// Upload with plus icon - for file upload button
export const UploadPlusIcon = createIcon(
  <>
    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
    <line x1="8" y1="12" x2="16" y2="12" />
  </>
);

// Panel collapse icon - for collapsing status panel
export const PanelCollapseIcon = createIcon(
  <>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <line x1="3" y1="15" x2="21" y2="15" />
    <polyline points="15 19 12 16 9 19" />
  </>
);

// ==================== Settings & Config ====================

export const SettingsIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z" />
  </>
);

export const GearIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z" />
  </>
);

export const SlidersIcon = createIcon(
  <>
    <line x1="4" y1="21" x2="4" y2="14" />
    <line x1="4" y1="10" x2="4" y2="3" />
    <line x1="12" y1="21" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12" y2="3" />
    <line x1="20" y1="21" x2="20" y2="16" />
    <line x1="20" y1="12" x2="20" y2="3" />
    <line x1="1" y1="14" x2="7" y2="14" />
    <line x1="9" y1="8" x2="15" y2="8" />
    <line x1="17" y1="16" x2="23" y2="16" />
  </>
);

// ==================== Status & Indicators ====================

export const CheckCircleIcon = createIcon(
  <>
    <path d="M22 11.08V12a10 10 0 11-5.93-9.14" />
    <polyline points="22 4 12 14.01 9 11.01" />
  </>
);

export const XCircleIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <path d="M15 9l-6 6" />
    <path d="M9 9l6 6" />
  </>
);

export const AlertIcon = createIcon(
  <>
    <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
    <line x1="12" y1="9" x2="12" y2="13" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </>
);

export const InfoIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="16" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12.01" y2="8" />
  </>
);

export const HelpIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <path d="M9.09 9a3 3 0 015.83 1c0 2-3 3-3 3" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </>
);

// ==================== Search ====================

export const SearchIcon = createIcon(
  <>
    <circle cx="11" cy="11" r="8" />
    <path d="M21 21l-4.35-4.35" />
  </>
);

export const FilterIcon = createIcon(
  <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
);

export const ReplaceIcon = createIcon(
  <>
    <path d="M12 3v18" />
    <path d="M5 12l7-7 7 7" />
  </>
);

// ==================== Agent & Users ====================

export const UserIcon = createIcon(
  <>
    <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </>
);

export const UsersIcon = createIcon(
  <>
    <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
    <circle cx="9" cy="7" r="4" />
    <path d="M23 21v-2a4 4 0 00-3-3.87" />
    <path d="M16 3.13a4 4 0 010 7.75" />
  </>
);

export const RobotIcon = createIcon(
  <>
    <line x1="12" y1="2" x2="12" y2="5" />
    <circle cx="12" cy="2" r="1.2" fill="currentColor" stroke="none" />
    <rect x="4" y="5" width="16" height="12" rx="3" />
    <circle cx="9" cy="11" r="2" />
    <circle cx="8.3" cy="10.3" r="0.6" fill="currentColor" stroke="none" />
    <circle cx="15" cy="11" r="2" />
    <circle cx="14.3" cy="10.3" r="0.6" fill="currentColor" stroke="none" />
    <path d="M10 14.5q2 1.5 4 0" />
    <path d="M8 17v2q0 1 1 1h6q1 0 1-1v-2" />
    <line x1="4" y1="9" x2="2" y2="9" />
    <line x1="20" y1="9" x2="22" y2="9" />
  </>
);

export const AgentIcon = RobotIcon;

// ==================== Shield & Security ====================

export const ShieldIcon = createIcon(<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />);

export const ShieldCheckIcon = createIcon(
  <>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    <path d="M9 12l2 2 4-4" />
  </>
);

export const LockIcon = createIcon(
  <>
    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 0110 0v4" />
  </>
);

export const UnlockIcon = createIcon(
  <>
    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 019.9-1" />
  </>
);

// ==================== Layout & UI ====================

export const LayersIcon = createIcon(
  <>
    <polygon points="12 2 2 7 12 12 22 7 12 2" />
    <polyline points="2 17 12 22 22 17" />
    <polyline points="2 12 12 17 22 12" />
  </>
);

export const LayoutIcon = createIcon(
  <>
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <line x1="3" y1="9" x2="21" y2="9" />
    <line x1="9" y1="21" x2="9" y2="9" />
  </>
);

export const GridIcon = createIcon(
  <>
    <rect x="3" y="3" width="7" height="7" />
    <rect x="14" y="3" width="7" height="7" />
    <rect x="14" y="14" width="7" height="7" />
    <rect x="3" y="14" width="7" height="7" />
  </>
);

export const ListIcon = createIcon(
  <>
    <line x1="8" y1="6" x2="21" y2="6" />
    <line x1="8" y1="12" x2="21" y2="12" />
    <line x1="8" y1="18" x2="21" y2="18" />
    <line x1="3" y1="6" x2="3.01" y2="6" />
    <line x1="3" y1="12" x2="3.01" y2="12" />
    <line x1="3" y1="18" x2="3.01" y2="18" />
  </>
);

export const MenuIcon = createIcon(
  <>
    <line x1="3" y1="12" x2="21" y2="12" />
    <line x1="3" y1="6" x2="21" y2="6" />
    <line x1="3" y1="18" x2="21" y2="18" />
  </>
);

export const MoreIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="1" />
    <circle cx="19" cy="12" r="1" />
    <circle cx="5" cy="12" r="1" />
  </>
);

export const MaximizeIcon = createIcon(
  <>
    <path d="M8 3H5a2 2 0 00-2 2v3m18 0V5a2 2 0 00-2-2h-3m0 18h3a2 2 0 002-2v-3M3 16v3a2 2 0 002 2h3" />
  </>
);

export const MinimizeIcon = createIcon(
  <>
    <path d="M8 3v3a2 2 0 01-2 2H3m18 0h-3a2 2 0 01-2-2V3m0 18v-3a2 2 0 012-2h3M3 16h3a2 2 0 012 2v3" />
  </>
);

// ==================== History & Time ====================

export const HistoryIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <polyline points="12 6 12 12 16 14" />
  </>
);

export const ClockIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <polyline points="12 6 12 12 16 14" />
  </>
);

export const CalendarIcon = createIcon(
  <>
    <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
    <line x1="16" y1="2" x2="16" y2="6" />
    <line x1="8" y1="2" x2="8" y2="6" />
    <line x1="3" y1="10" x2="21" y2="10" />
  </>
);

// ==================== Code & Development ====================

export const TerminalIcon = createIcon(
  <>
    <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
    <path d="M8 21h8" />
    <path d="M12 17v4" />
  </>
);

export const CodeIcon = createIcon(
  <>
    <polyline points="16 18 22 12 16 6" />
    <polyline points="8 6 2 12 8 18" />
  </>
);

export const GitBranchIcon = createIcon(
  <>
    <line x1="6" y1="3" x2="6" y2="15" />
    <circle cx="18" cy="6" r="3" />
    <circle cx="6" cy="18" r="3" />
    <path d="M18 9a9 9 0 01-9 9" />
  </>
);

export const GitCommitIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="4" />
    <line x1="1.05" y1="12" x2="7" y2="12" />
    <line x1="17.01" y1="12" x2="22.96" y2="12" />
  </>
);

export const BugIcon = createIcon(
  <>
    <rect x="8" y="6" width="8" height="14" rx="4" />
    <path d="M19 10h2" />
    <path d="M3 10h2" />
    <path d="M19 6h1a2 2 0 012 2" />
    <path d="M2 8a2 2 0 012-2h1" />
    <path d="M19 14h2" />
    <path d="M3 14h2" />
  </>
);

export const DatabaseIcon = createIcon(
  <>
    <ellipse cx="12" cy="5" rx="9" ry="3" />
    <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
    <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
  </>
);

export const ServerIcon = createIcon(
  <>
    <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
    <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
    <line x1="6" y1="6" x2="6.01" y2="6" />
    <line x1="6" y1="18" x2="6.01" y2="18" />
  </>
);

// ==================== Media & Display ====================

export const ImageIcon = createIcon(
  <>
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <polyline points="21 15 16 10 5 21" />
  </>
);

export const EyeIcon = createIcon(
  <>
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </>
);

export const EyeOffIcon = createIcon(
  <>
    <path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19m-6.72-1.07a3 3 0 11-4.24-4.24" />
    <line x1="1" y1="1" x2="23" y2="23" />
  </>
);

export const DownloadIcon = createIcon(
  <>
    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
    <polyline points="7 10 12 15 17 10" />
    <line x1="12" y1="15" x2="12" y2="3" />
  </>
);

export const UploadIcon = createIcon(
  <>
    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </>
);

// ==================== Misc ====================

export const StarIcon = createIcon(
  <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
);

export const HeartIcon = createIcon(
  <path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z" />
);

export const BookmarkIcon = createIcon(
  <path d="M19 21l-7-5-7 5V5a2 2 0 012-2h10a2 2 0 012 2z" />
);

export const LinkIcon = createIcon(
  <>
    <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" />
    <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" />
  </>
);

export const ExternalLinkIcon = createIcon(
  <>
    <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6" />
    <polyline points="15 3 21 3 21 9" />
    <line x1="10" y1="14" x2="21" y2="3" />
  </>
);

export const GlobeIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <line x1="2" y1="12" x2="22" y2="12" />
    <path d="M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z" />
  </>
);

export const CloudIcon = createIcon(<path d="M18 10h-1.26A8 8 0 109 20h9a5 5 0 000-10z" />);

export const WifiIcon = createIcon(
  <>
    <path d="M5 12.55a11 11 0 0114.08 0" />
    <path d="M1.42 9a16 16 0 0121.16 0" />
    <path d="M8.53 16.11a6 6 0 016.95 0" />
    <line x1="12" y1="20" x2="12.01" y2="20" />
  </>
);

export const ZapIcon = createIcon(<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />);

export const SparklesIcon = createIcon(
  <>
    <line x1="4" y1="20" x2="14" y2="10" />
    <path d="M13 9l2 2" />
    <path d="M17 3l.8 2.2L20 6l-2.2.8L17 9l-.8-2.2L14 6l2.2-.8z" />
    <path d="M20 11l.5 1.3L22 13l-1.5.7L20 15l-.5-1.3L18 13l1.5-.7z" />
    <path d="M13 1l.4 1.1L15 2.5l-1.6.6L13 4.5l-.4-1.4L11 2.5l1.6-.4z" />
  </>
);

export const MagicIcon = SparklesIcon;

export const BrainIcon = createIcon(
  <>
    <path d="M9.5 2A2.5 2.5 0 0112 4.5v15a2.5 2.5 0 01-4.96.44 2.5 2.5 0 01-2.96-3.08 3 3 0 01-.34-5.58 2.5 2.5 0 011.32-4.24 2.5 2.5 0 011.98-3A2.5 2.5 0 019.5 2z" />
    <path d="M14.5 2A2.5 2.5 0 0012 4.5v15a2.5 2.5 0 004.96.44 2.5 2.5 0 002.96-3.08 3 3 0 00.34-5.58 2.5 2.5 0 00-1.32-4.24 2.5 2.5 0 00-1.98-3A2.5 2.5 0 0014.5 2z" />
  </>
);

export const LightbulbIcon = createIcon(
  <>
    <path d="M9 18h6" />
    <path d="M10 22h4" />
    <path d="M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0018 8 6 6 0 006 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 018.91 14" />
  </>
);

export const TargetIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <circle cx="12" cy="12" r="6" />
    <circle cx="12" cy="12" r="2" />
  </>
);

export const HashIcon = createIcon(
  <>
    <line x1="4" y1="9" x2="20" y2="9" />
    <line x1="4" y1="15" x2="20" y2="15" />
    <line x1="10" y1="3" x2="8" y2="21" />
    <line x1="16" y1="3" x2="14" y2="21" />
  </>
);

export const AtSignIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="4" />
    <path d="M16 8v5a3 3 0 006 0v-1a10 10 0 10-3.92 7.94" />
  </>
);

export const DollarIcon = createIcon(
  <>
    <line x1="12" y1="1" x2="12" y2="23" />
    <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6" />
  </>
);

export const PercentIcon = createIcon(
  <>
    <line x1="19" y1="5" x2="5" y2="19" />
    <circle cx="6.5" cy="6.5" r="2.5" />
    <circle cx="17.5" cy="17.5" r="2.5" />
  </>
);

export const PlusIcon = createIcon(
  <>
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </>
);

export const MinusIcon = createIcon(<line x1="5" y1="12" x2="19" y2="12" />);

export const EyeDropperIcon = createIcon(
  <>
    <path d="M2 22l1-1h3l9-9" />
    <path d="M3 21v-3l9-9" />
    <path d="M14.5 5.5L18 2l4 4-3.5 3.5" />
    <path d="M12 8l4 4" />
    <path d="M2 2l20 20" />
  </>
);

export const PenIcon = createIcon(
  <path d="M12 20h9M16.5 3.5a2.121 2.121 0 013 3L7 19l-4 1 1-4L16.5 3.5z" />
);

export const ClipboardIcon = createIcon(
  <>
    <path d="M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2" />
    <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
  </>
);

export const ClipboardCheckIcon = createIcon(
  <>
    <path d="M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2" />
    <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
    <path d="M9 14l2 2 4-4" />
  </>
);

export const TaskIcon = createIcon(
  <>
    <rect x="5" y="2" width="14" height="20" rx="2" />
    <path d="M9 2v2h6V2" />
    <path d="M9 10l1.5 1.5L14 8" />
    <line x1="9" y1="15" x2="15" y2="15" />
    <line x1="9" y1="18" x2="13" y2="18" />
  </>
);

export const SubtaskIcon = createIcon(
  <>
    <rect x="5" y="2" width="14" height="20" rx="2" />
    <path d="M9 2v2h6V2" />
    <path d="M9 10l1.5 1.5L14 8" />
    <line x1="9" y1="15" x2="15" y2="15" />
    <line x1="9" y1="18" x2="13" y2="18" />
  </>
);

export const DiffIcon = createIcon(
  <>
    <path d="M12 3v18" />
    <path d="M5 12h14" />
  </>
);

// Dual-column diff view icon — left/right panes (标准 diff 对照视图)
export const DiffViewIcon = createIcon(
  <>
    <rect x="3" y="5" width="8" height="14" rx="1.5" />
    <rect x="13" y="5" width="8" height="14" rx="1.5" />
  </>
);

// Keep-all icon — double checkmarks ("全部确认/保留所有改动")
export const KeepAllIcon = createIcon(
  <>
    <path d="M18 6 7 17l-5-5" />
    <path d="m22 10-7.5 7.5L12 15" />
  </>
);

export const PlayIcon = createIcon(<polygon points="5 3 19 12 5 21 5 3" />);

export const PauseIcon = createIcon(
  <>
    <rect x="6" y="4" width="4" height="16" />
    <rect x="14" y="4" width="4" height="16" />
  </>
);

export const SkipIcon = createIcon(
  <>
    <polygon points="5 4 15 12 5 20 5 4" />
    <line x1="19" y1="5" x2="19" y2="19" />
  </>
);

export const RewindIcon = createIcon(
  <>
    <polygon points="11 19 2 12 11 5 11 19" />
    <polygon points="22 19 13 12 22 5 22 19" />
  </>
);

export const FastForwardIcon = createIcon(
  <>
    <polygon points="13 19 22 12 13 5 13 19" />
    <polygon points="2 19 11 12 2 5 2 19" />
  </>
);

export const VolumeIcon = createIcon(
  <>
    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
    <path d="M19.07 4.93a10 10 0 010 14.14M15.54 8.46a5 5 0 010 7.07" />
  </>
);

export const VolumeXIcon = createIcon(
  <>
    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
    <line x1="23" y1="9" x2="17" y2="15" />
    <line x1="17" y1="9" x2="23" y2="15" />
  </>
);

export const BellIcon = createIcon(
  <>
    <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" />
    <path d="M13.73 21a2 2 0 01-3.46 0" />
  </>
);

export const BellOffIcon = createIcon(
  <>
    <path d="M13.73 21a2 2 0 01-3.46 0" />
    <path d="M18.63 13A17.89 17.89 0 0118 8" />
    <path d="M6.26 6.26A5.86 5.86 0 006 8c0 7-3 9-3 9h14" />
    <path d="M18 8a6 6 0 00-9.33-5" />
    <line x1="1" y1="1" x2="23" y2="23" />
  </>
);

export const PinIcon = createIcon(
  <>
    <path d="M15 4.5l-4 4L7 10l-1.5 1.5 7 7L14 17l2-4 4-4" />
    <path d="M9 15l-4.5 4.5" />
    <path d="M14.5 4L20 9.5" />
  </>
);

export const PinOffIcon = createIcon(
  <>
    <line x1="2" y1="2" x2="22" y2="22" />
    <path d="M12 17v5" />
    <path d="M9 10.5L5 21l6-3.5" />
    <path d="M15 5.5L21 16l-2.5 1.5" />
    <path d="M12 2v2" />
    <path d="M12 6l4 4" />
  </>
);

export const TagIcon = createIcon(
  <>
    <path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z" />
    <line x1="7" y1="7" x2="7.01" y2="7" />
  </>
);

export const TagsIcon = createIcon(
  <>
    <path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z" />
    <line x1="7" y1="7" x2="7.01" y2="7" />
    <line x1="11" y1="11" x2="11.01" y2="11" />
  </>
);

export const BookmarkFilledIcon = createIcon(
  <path d="M19 21l-7-5-7 5V5a2 2 0 012-2h10a2 2 0 012 2z" fill="currentColor" />
);

export const StarFilledIcon = createIcon(
  <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" fill="currentColor" />
);

export const HeartFilledIcon = createIcon(
  <path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z" fill="currentColor" />
);

export const CircleIcon = createIcon(<circle cx="12" cy="12" r="10" />);

export const CircleFilledIcon = createIcon(<circle cx="12" cy="12" r="10" fill="currentColor" />);

export const SquareIcon = createIcon(<rect x="3" y="3" width="18" height="18" rx="2" ry="2" />);

export const SquareFilledIcon = createIcon(<rect x="3" y="3" width="18" height="18" rx="2" ry="2" fill="currentColor" />);

export const TriangleIcon = createIcon(<path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />);

export const DiamondIcon = createIcon(<rect x="4.5" y="4.5" width="15" height="15" rx="1" transform="rotate(45 12 12)" />);

// ==================== Self-Explanatory Functional Icons ====================
// (Part A · 图标自表意优化) 语义清晰的专用图标,替换原"看图标猜不出功能"的图形。
// 设计:气泡/圆柱/文件+对勾等承载"会发生什么"的语义,而非抽象符号。

// 回退上一轮对话 — 气泡 + 左箭头(区别于刷新/重置)
export const ChatRewindIcon = createIcon(
  <>
    <path d="M21 11.5a8.5 8.5 0 0 1-12.6 7.4L3 21l1.9-5.4A8.5 8.5 0 0 1 12.5 3a8.5 8.5 0 0 1 8.5 8.5z" />
    <path d="M9 11.5 7 13.5 9 15.5" />
    <path d="M7 13.5h5" />
  </>
);

// 缓存写入 token — 圆柱 + 下箭头(与"缓存读取"统一为圆柱系,箭头表写入)
export const CacheWriteIcon = createIcon(
  <>
    <path d="M12 3v6" />
    <path d="m9 6 3 3 3-3" />
    <ellipse cx="12" cy="16" rx="7" ry="2.6" />
    <path d="M5 16v2c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6v-2" />
  </>
);

// 新开标签页/窗口 — 窗口 + 加号(区别于田字格布局 与 PlusIcon 新建会话)
export const NewTabIcon = createIcon(
  <>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M3 8h18" />
    <path d="M17 13.5v4" />
    <path d="m15 15.5 2-2 2 2" />
  </>
);

// 自动接受编辑模式 (acceptEdits) — 文件 + 对勾
export const FileCheckIcon = createIcon(
  <>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 3v5h6" />
    <path d="m8.5 14.5 2 2 4-4" />
  </>
);

// 全字匹配 — ab + 下划线(对齐 VS Code / IntelliJ 搜索栏,零学习成本)
export const WholeWordIcon = createIcon(
  <>
    <text x="12" y="15" textAnchor="middle" fontSize="12.5" fontFamily="ui-sans-serif, system-ui, sans-serif" fontWeight="700" fill="currentColor" stroke="none">ab</text>
    <path d="M7 17h10" />
  </>
);

// 正则匹配 — 字符 .*(正则世界语)
export const RegexIcon = createIcon(
  <text x="12" y="17" textAnchor="middle" fontSize="16" fontFamily="ui-monospace, 'Cascadia Code', monospace" fontWeight="700" fill="currentColor" stroke="none">.*</text>
);

// 深度搜索(历史)— 放大镜 + 内部十字钻取(区别于刷新/同步)
export const SearchDeepIcon = createIcon(
  <>
    <circle cx="11" cy="11" r="7" />
    <path d="m21 21-4.3-4.3" />
    <path d="M8 11h6" />
    <path d="M11 8v6" />
  </>
);

// Edits / 文件改动标签 — 文件 + diff 行(明确"列改动"非"编辑动作")
export const FileDiffIcon = createIcon(
  <>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 3v5h6" />
    <path d="M8.5 12.5h3l-3 4h3" />
    <path d="M16 11v5" />
    <path d="M13.5 13.5h5" />
  </>
);

// 命令行 / 运行时(Node 进程)入口 — 终端 >_
export const CommandLineIcon = createIcon(
  <>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M3 9h18" />
    <path d="m7 13 3 3-3 3" />
    <path d="M13 16h4" />
  </>
);

// 配额 / 用量查询 — 量表 (gauge,仪表表盘 = 余额计量)
export const GaugeIcon = createIcon(
  <>
    <path d="M3.5 19a10 10 0 1 1 17 0" />
    <path d="m12 14 4-4" />
    <circle cx="12" cy="14" r="1.2" fill="currentColor" stroke="none" />
  </>
);

// 日志 / 输出 — 文档 + 行(MCP 日志等)
export const LogIcon = createIcon(
  <>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 3v5h6" />
    <path d="M8 12h8" />
    <path d="M8 15.5h8" />
    <path d="M8 9h2" />
  </>
);

// 转换为 CLI 会话 — 终端 + 箭头(目标形态可见)
export const TerminalArrowIcon = createIcon(
  <>
    <rect x="3" y="4" width="14" height="16" rx="2" />
    <path d="m7 9 3 3-3 3" />
    <path d="M17 12h4" />
    <path d="M19 10l2 2-2 2" />
  </>
);

// 命名空间符号 — 花括号 {}
export const BracesIcon = createIcon(
  <>
    <path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1" />
    <path d="M16 3h1a2 2 0 0 1 2 2v5c0 1.1.9 2 2 2a2 2 0 0 1-2 2v5a2 2 0 0 1-2 2h-1" />
  </>
);

// 颜色符号 — 调色板
export const PaletteIcon = createIcon(
  <path d="M12 2.7 17.7 8.3a8 8 0 1 1-11.4 0z" />
);

// 文本符号 — 字母 A
export const TypeIcon = createIcon(
  <>
    <polyline points="4 7 4 4 20 4 20 7" />
    <path d="M9 20h6" />
    <path d="M12 4v16" />
  </>
);

// 启用开关 — 电源
export const PowerIcon = createIcon(
  <>
    <path d="M12 2v10" />
    <path d="M18.4 6.6a9 9 0 1 1-12.8 0" />
  </>
);

// 禁用 — 禁止圈
export const BanIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="9" />
    <path d="m5.6 5.6 12.8 12.8" />
  </>
);

// 拖拽手柄 — 六点
export const GripIcon = createIcon(
  <>
    <circle cx="9" cy="6" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="9" cy="12" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="9" cy="18" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="15" cy="6" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="15" cy="12" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="15" cy="18" r="1.3" fill="currentColor" stroke="none" />
  </>
);

// ==================== History Page Icons ====================

export const CheckAllIcon = createIcon(
  <>
    <rect x="8" y="2" width="13" height="18" rx="2" />
    <path d="M4 6l2 2 4-4" />
    <path d="M4 12l2 2 4-4" />
    <path d="M4 18l2 2 4-4" />
  </>
);

export const ClearAllIcon = createIcon(
  <>
    <rect x="8" y="2" width="13" height="18" rx="2" />
    <path d="M4 7l4 4M8 7l-4 4" />
    <path d="M4 13l4 4M8 13l-4 4" />
  </>
);

export const SyncIcon: React.FC<IconProps & { spinning?: boolean }> = ({
  size = 16,
  className,
  style,
  spinning = false,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.8"
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    style={{
      ...style,
      animation: spinning ? 'icon-spin 1s cubic-bezier(0.4, 0, 0.2, 1) infinite' : undefined,
      willChange: spinning ? 'transform' : undefined,
    }}
  >
    <path d="M21 2v6h-6" />
    <path d="M3 12a9 9 0 0115.36-6.36L21 8" />
    <path d="M3 22v-6h6" />
    <path d="M21 12a9 9 0 01-15.36 6.36L3 16" />
  </svg>
);

// ==================== Missing Codicon Mappings ====================

export const GraphIcon = createIcon(
  <>
    <circle cx="12" cy="5" r="2" />
    <circle cx="5" cy="19" r="2" />
    <circle cx="19" cy="19" r="2" />
    <path d="M12 7v4m0 0l-5 6m5-6l5 6" />
  </>
);

export const CommentIcon = createIcon(
  <>
    <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z" />
  </>
);

export const InboxIcon = createIcon(
  <>
    <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
    <path d="M5.45 5.11L2 12v6a2 2 0 002 2h16a2 2 0 002-2v-6l-3.45-6.89A2 2 0 0016.76 4H7.24a2 2 0 00-1.79 1.11z" />
  </>
);

export const KeyIcon = createIcon(
  <>
    <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 11-7.778 7.778 5.5 5.5 0 017.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
  </>
);

export const NumberIcon = createIcon(
  <>
    <path d="M4 17l2-4m2 4l2-8m2 8l2-6m2 6l2-4" />
    <circle cx="4" cy="17" r="1" fill="currentColor" stroke="none" />
    <circle cx="8" cy="13" r="1" fill="currentColor" stroke="none" />
    <circle cx="12" cy="17" r="1" fill="currentColor" stroke="none" />
    <circle cx="16" cy="11" r="1" fill="currentColor" stroke="none" />
    <circle cx="20" cy="17" r="1" fill="currentColor" stroke="none" />
  </>
);

export const RocketIcon = createIcon(
  <>
    <path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 00-2.91-.09z" />
    <path d="M12 15l-3-3a22 22 0 012-3.95A12.88 12.88 0 0122 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 01-4 2z" />
    <path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0" />
    <path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5" />
  </>
);

export const ServerProcessIcon = createIcon(
  <>
    <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
    <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
    <line x1="6" y1="6" x2="6.01" y2="6" />
    <line x1="6" y1="18" x2="6.01" y2="18" />
  </>
);

export const CreditCardIcon = createIcon(
  <>
    <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
    <line x1="1" y1="10" x2="23" y2="10" />
  </>
);

export const FolderLibraryIcon = createIcon(
  <>
    <path d="M4 20h16a2 2 0 002-2V8a2 2 0 00-2-2h-7.93a2 2 0 01-1.66-.9l-.82-1.2A2 2 0 007.93 3H4a2 2 0 00-2 2v13c0 1.1.9 2 2 2z" />
    <path d="M8 12h8M8 16h5" />
  </>
);

export const GraphLineIcon = createIcon(
  <>
    <path d="M3 3v18h18" />
    <path d="M7 16l4-4 4 2 5-6" />
  </>
);

export const BookIcon = createIcon(
  <>
    <path d="M4 19.5A2.5 2.5 0 016.5 17H20" />
    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" />
  </>
);

export const ExtensionsIcon = createIcon(
  <>
    <path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4" />
    <polyline points="10 17 15 12 10 7" />
    <line x1="15" y1="12" x2="3" y2="12" />
  </>
);

export const HomeIcon = createIcon(
  <>
    <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" />
    <polyline points="9 22 9 12 15 12 15 22" />
  </>
);

export const KebabVerticalIcon = createIcon(
  <>
    <circle cx="12" cy="5" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="12" cy="19" r="1.5" fill="currentColor" stroke="none" />
  </>
);

export const FolderOpenedIcon = createIcon(
  <>
    <path d="M5 19a2 2 0 01-2-2V7a2 2 0 012-2h4l2 2h6a2 2 0 012 2v1" />
    <path d="M2 14l3.5 5h13l3.5-5" />
  </>
);

export const KeyboardIcon = createIcon(
  <>
    <rect x="2" y="4" width="20" height="16" rx="2" ry="2" />
    <line x1="6" y1="8" x2="6.01" y2="8" />
    <line x1="10" y1="8" x2="10.01" y2="8" />
    <line x1="14" y1="8" x2="14.01" y2="8" />
    <line x1="18" y1="8" x2="18.01" y2="8" />
    <line x1="6" y1="12" x2="6.01" y2="12" />
    <line x1="10" y1="12" x2="10.01" y2="12" />
    <line x1="14" y1="12" x2="14.01" y2="12" />
    <line x1="18" y1="12" x2="18.01" y2="12" />
    <line x1="8" y1="16" x2="16" y2="16" />
  </>
);

export const GitHubIcon = createIcon(
  <path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 00-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0020 4.77 5.07 5.07 0 0019.91 1S18.73.65 16 2.48a13.38 13.38 0 00-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 005 4.77a5.44 5.44 0 00-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 009 18.13V22" />
);

export const WrenchIcon = createIcon(
  <>
    <path d="M14.7 6.3a1 1 0 000 1.4l1.6 1.6a1 1 0 001.4 0l3.77-3.77a6 6 0 01-7.94 7.94l-6.91 6.91a2.12 2.12 0 01-3-3l6.91-6.91a6 6 0 017.94-7.94l-3.76 3.76z" />
  </>
);

// ==================== Additional Codicon Mappings ====================

export const CircleSmallIcon = createIcon(<circle cx="12" cy="12" r="5" />);

export const CircleLargeFilledIcon = createIcon(<circle cx="12" cy="12" r="10" fill="currentColor" />);

export const CircleOutlineIcon = createIcon(<circle cx="12" cy="12" r="10" />);

export const CircleSlashIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="10" />
    <path d="M4.93 4.93l14.14 14.14" />
  </>
);

export const CommentDiscussionIcon = createIcon(
  <>
    <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
    <path d="M12 8v4" />
    <path d="M10 10h4" />
  </>
);

export const DebugAltIcon = createIcon(
  <>
    <path d="M12 2a10 10 0 100 20 10 10 0 000-20z" />
    <path d="M8 15l-2 3" />
    <path d="M16 15l2 3" />
    <path d="M8 9l4 3 4-3" />
  </>
);

export const DebugDisconnectIcon = createIcon(
  <>
    <path d="M12 2a10 10 0 100 20 10 10 0 000-20z" />
    <path d="M4.93 4.93l14.14 14.14" />
  </>
);

export const DebugRestartIcon = createIcon(
  <>
    <path d="M12 2a10 10 0 100 20 10 10 0 000-20z" />
    <path d="M8 9l-3 3 3 3" />
    <path d="M16 9l3 3-3 3" />
  </>
);

export const DebugStopIcon = createIcon(
  <>
    <path d="M12 2a10 10 0 100 20 10 10 0 000-20z" />
    <rect x="9" y="9" width="6" height="6" rx="1" />
  </>
);

export const DesktopDownloadIcon = createIcon(
  <>
    <rect x="3" y="3" width="18" height="13" rx="2" />
    <path d="M8 21h8" />
    <path d="M12 17v4" />
    <path d="M7 10l5 5 5-5" />
  </>
);

export const FilePdfIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
    <text x="12" y="17" textAnchor="middle" fontSize="7" fontWeight="700" fill="currentColor" stroke="none" fontFamily="sans-serif">PDF</text>
  </>
);

export const FileSymlinkIcon = createIcon(
  <>
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <path d="M14 2v6h6" />
    <path d="M12 18a3 3 0 100-6 3 3 0 000 6z" />
    <path d="M9.5 14.5L12 17l2.5-2.5" />
  </>
);

export const FlameIcon = createIcon(
  <>
    <path d="M8.5 14.5A2.5 2.5 0 0011 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 11-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 002.5 2.5z" />
  </>
);

export const ListTreeIcon = createIcon(
  <>
    <path d="M3 4h18" />
    <path d="M3 12h12" />
    <path d="M3 20h8" />
    <path d="M15 12l3 3-3 3" />
  </>
);

export const MailIcon = createIcon(
  <>
    <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
    <polyline points="22,6 12,13 2,6" />
  </>
);

export const PassIcon = createIcon(
  <>
    <path d="M22 11.08V12a10 10 0 11-5.93-9.14" />
    <polyline points="22 4 12 14.01 9 11.01" />
  </>
);

// Alias: codicon-pencil -> PencilIcon (reuses PenIcon SVG)
export const PencilIcon = PenIcon;

export const PlugIcon = createIcon(
  <>
    <path d="M12 22v-5" />
    <path d="M9 8V2" />
    <path d="M15 8V2" />
    <path d="M18 8v5a6 6 0 01-6 6 6 6 0 01-6-6V8z" />
  </>
);

// Alias: codicon-tools -> ToolsIcon (reuses WrenchIcon SVG)
export const ToolsIcon = WrenchIcon;

export const SymbolClassIcon = createIcon(
  <>
    <path d="M12 3L3 12l9 9 9-9-9-9z" />
    <path d="M9 12l3 3 3-3" />
  </>
);

export const SymbolMiscIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2v4" />
    <path d="M12 18v4" />
    <path d="M4.93 4.93l2.83 2.83" />
    <path d="M16.24 16.24l2.83 2.83" />
    <path d="M2 12h4" />
    <path d="M18 12h4" />
    <path d="M4.93 19.07l2.83-2.83" />
    <path d="M16.24 7.76l2.83-2.83" />
  </>
);

export const SymbolNamespaceIcon = createIcon(
  <>
    <path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1" />
    <path d="M16 3h1a2 2 0 0 1 2 2v5c0 1.1.9 2 2 2a2 2 0 0 1-2 2v5a2 2 0 0 1-2 2h-1" />
  </>
);

export const SymbolPropertyIcon = createIcon(
  <>
    <circle cx="12" cy="12" r="9" />
    <path d="M9 12h6" />
    <path d="M12 9v6" />
  </>
);

// Export all icons as a map for convenience
export const Icons = {
  back: BackIcon,
  forward: ForwardIcon,
  close: CloseIcon,
  check: CheckIcon,
  chevronDown: ChevronDownIcon,
  chevronUp: ChevronUpIcon,
  chevronLeft: ChevronLeftIcon,
  chevronRight: ChevronRightIcon,
  arrowUp: ArrowUpIcon,
  arrowDown: ArrowDownIcon,
  send: SendIcon,
  stop: StopIcon,
  message: MessageIcon,
  chat: ChatIcon,
  file: FileIcon,
  fileCode: FileCodeIcon,
  fileText: FileTextIcon,
  folder: FolderIcon,
  save: SaveIcon,
  edit: EditIcon,
  trash: TrashIcon,
  copy: CopyIcon,
  paste: PasteIcon,
  undo: UndoIcon,
  redo: RedoIcon,
  refresh: RefreshIcon,
  rotateCounterClockwise: RotateCounterClockwiseIcon,
  attach: AttachIcon,
  settings: SettingsIcon,
  gear: GearIcon,
  sliders: SlidersIcon,
  checkCircle: CheckCircleIcon,
  xCircle: XCircleIcon,
  alert: AlertIcon,
  info: InfoIcon,
  help: HelpIcon,
  search: SearchIcon,
  filter: FilterIcon,
  replace: ReplaceIcon,
  user: UserIcon,
  users: UsersIcon,
  robot: RobotIcon,
  agent: AgentIcon,
  shield: ShieldIcon,
  shieldCheck: ShieldCheckIcon,
  lock: LockIcon,
  unlock: UnlockIcon,
  layers: LayersIcon,
  layout: LayoutIcon,
  grid: GridIcon,
  list: ListIcon,
  menu: MenuIcon,
  more: MoreIcon,
  maximize: MaximizeIcon,
  minimize: MinimizeIcon,
  history: HistoryIcon,
  clock: ClockIcon,
  calendar: CalendarIcon,
  terminal: TerminalIcon,
  code: CodeIcon,
  gitBranch: GitBranchIcon,
  gitCommit: GitCommitIcon,
  bug: BugIcon,
  database: DatabaseIcon,
  server: ServerIcon,
  image: ImageIcon,
  eye: EyeIcon,
  eyeOff: EyeOffIcon,
  download: DownloadIcon,
  upload: UploadIcon,
  star: StarIcon,
  heart: HeartIcon,
  bookmark: BookmarkIcon,
  link: LinkIcon,
  externalLink: ExternalLinkIcon,
  globe: GlobeIcon,
  cloud: CloudIcon,
  wifi: WifiIcon,
  zap: ZapIcon,
  sparkles: SparklesIcon,
  magic: MagicIcon,
  brain: BrainIcon,
  lightbulb: LightbulbIcon,
  target: TargetIcon,
  hash: HashIcon,
  atSign: AtSignIcon,
  dollar: DollarIcon,
  percent: PercentIcon,
  plus: PlusIcon,
  minus: MinusIcon,
  eyeDropper: EyeDropperIcon,
  pen: PenIcon,
  clipboard: ClipboardIcon,
  clipboardCheck: ClipboardCheckIcon,
  task: TaskIcon,
  subtask: SubtaskIcon,
  diff: DiffIcon,
  diffView: DiffViewIcon,
  keepAll: KeepAllIcon,
  play: PlayIcon,
  pause: PauseIcon,
  skip: SkipIcon,
  rewind: RewindIcon,
  fastForward: FastForwardIcon,
  volume: VolumeIcon,
  volumeX: VolumeXIcon,
  bell: BellIcon,
  bellOff: BellOffIcon,
  pin: PinIcon,
  pinOff: PinOffIcon,
  tag: TagIcon,
  tags: TagsIcon,
  bookmarkFilled: BookmarkFilledIcon,
  starFilled: StarFilledIcon,
  heartFilled: HeartFilledIcon,
  circle: CircleIcon,
  circleFilled: CircleFilledIcon,
  square: SquareIcon,
  squareFilled: SquareFilledIcon,
  triangle: TriangleIcon,
  diamond: DiamondIcon,
  checklist: TaskIcon,
  checkAll: CheckAllIcon,
  clearAll: ClearAllIcon,
  chatRewind: ChatRewindIcon,
  cacheWrite: CacheWriteIcon,
  newTab: NewTabIcon,
  fileCheck: FileCheckIcon,
  wholeWord: WholeWordIcon,
  regex: RegexIcon,
  searchDeep: SearchDeepIcon,
  fileDiff: FileDiffIcon,
  commandLine: CommandLineIcon,
  gauge: GaugeIcon,
  log: LogIcon,
  terminalArrow: TerminalArrowIcon,
  braces: BracesIcon,
  palette: PaletteIcon,
  type: TypeIcon,
  power: PowerIcon,
  ban: BanIcon,
  grip: GripIcon,
  sync: SyncIcon,
  circleSmall: CircleSmallIcon,
  circleLargeFilled: CircleLargeFilledIcon,
  circleOutline: CircleOutlineIcon,
  circleSlash: CircleSlashIcon,
  commentDiscussion: CommentDiscussionIcon,
  debugAlt: DebugAltIcon,
  debugDisconnect: DebugDisconnectIcon,
  debugRestart: DebugRestartIcon,
  debugStop: DebugStopIcon,
  desktopDownload: DesktopDownloadIcon,
  filePdf: FilePdfIcon,
  fileSymlink: FileSymlinkIcon,
  flame: FlameIcon,
  listTree: ListTreeIcon,
  mail: MailIcon,
  pass: PassIcon,
  pencil: PencilIcon,
  plug: PlugIcon,
  tools: ToolsIcon,
  symbolClass: SymbolClassIcon,
  symbolMisc: SymbolMiscIcon,
  symbolNamespace: SymbolNamespaceIcon,
  symbolProperty: SymbolPropertyIcon,
} as const;

export type IconName = keyof typeof Icons;

// Helper component to render icon by name
export const Icon: React.FC<IconProps & { name: IconName }> = ({ name, ...props }) => {
  const IconComponent = Icons[name];
  return <IconComponent {...props} />;
};

/**
 * Maps codicon class names (e.g. 'codicon-error') to Icons.tsx components.
 * Used to migrate remaining dynamic codicon references.
 */
const CODICON_COMPONENT_MAP: Record<string, React.FC<IconProps>> = {
  'codicon-add': PlusIcon,
  'codicon-arrow-down': ArrowDownIcon,
  'codicon-arrow-left': ChevronLeftIcon,
  'codicon-arrow-right': ArrowRightIcon,
  'codicon-arrow-up': ArrowUpIcon,
  'codicon-check': CheckIcon,
  'codicon-checklist': TaskIcon,
  'codicon-chevron-down': ChevronDownIcon,
  'codicon-chevron-right': ChevronRightIcon,
  'codicon-circle-filled': CircleFilledIcon,
  'codicon-circle-large-filled': CircleLargeFilledIcon,
  'codicon-circle-outline': CircleOutlineIcon,
  'codicon-circle-slash': CircleSlashIcon,
  'codicon-circle-small': CircleSmallIcon,
  'codicon-close': CloseIcon,
  'codicon-cloud': CloudIcon,
  'codicon-code': CodeIcon,
  'codicon-comment': CommentIcon,
  'codicon-comment-discussion': CommentDiscussionIcon,
  'codicon-database': DatabaseIcon,
  'codicon-debug-alt': DebugAltIcon,
  'codicon-debug-disconnect': DebugDisconnectIcon,
  'codicon-debug-restart': DebugRestartIcon,
  'codicon-debug-stop': DebugStopIcon,
  'codicon-desktop-download': DesktopDownloadIcon,
  'codicon-diff': DiffIcon,
  'codicon-edit': EditIcon,
  'codicon-eye': EyeIcon,
  'codicon-eye-closed': EyeOffIcon,
  'codicon-file': FileIcon,
  'codicon-file-code': FileCodeIcon,
  'codicon-file-pdf': FilePdfIcon,
  'codicon-file-symlink': FileSymlinkIcon,
  'codicon-file-text': FileTextIcon,
  'codicon-filter': FilterIcon,
  'codicon-flame': FlameIcon,
  'codicon-folder': FolderIcon,
  'codicon-folder-opened': FolderOpenedIcon,
  'codicon-gear': GearIcon,
  'codicon-graph': GraphIcon,
  'codicon-home': HomeIcon,
  'codicon-inbox': InboxIcon,
  'codicon-key': KeyIcon,
  'codicon-keyboard': KeyboardIcon,
  'codicon-list-tree': ListTreeIcon,
  'codicon-lock': LockIcon,
  'codicon-mail': MailIcon,
  'codicon-menu': MenuIcon,
  'codicon-misc': SymbolMiscIcon,
  'codicon-more': KebabVerticalIcon,
  'codicon-pass': PassIcon,
  'codicon-pencil': PencilIcon,
  'codicon-play': PlayIcon,
  'codicon-plug': PlugIcon,
  'codicon-refresh': RefreshIcon,
  'codicon-rocket': RocketIcon,
  'codicon-search': SearchIcon,
  'codicon-send': SendIcon,
  'codicon-server': ServerIcon,
  'codicon-settings': SettingsIcon,
  'codicon-shield': ShieldIcon,
  'codicon-symbol-class': SymbolClassIcon,
  'codicon-symbol-namespace': SymbolNamespaceIcon,
  'codicon-symbol-property': SymbolPropertyIcon,
  'codicon-sync': SyncIcon,
  'codicon-tag': TagIcon,
  'codicon-terminal': TerminalIcon,
  'codicon-tools': ToolsIcon,
  'codicon-trash': TrashIcon,
  'codicon-undo': UndoIcon,
  'codicon-unlock': UnlockIcon,
  'codicon-warning': AlertIcon,
};

/**
 * Maps a codicon class name to a React icon component.
 *
 * @param codiconClass - e.g. 'codicon-error' or 'error'
 * @param size - icon size (default 16)
 * @param props - optional className / style
 * @returns A rendered React icon node; falls back to HelpIcon when unmapped.
 */
export function codiconToIcon(
  codiconClass: string,
  size: IconSize = 16,
  props?: { className?: string; style?: React.CSSProperties },
): React.ReactNode {
  const key = codiconClass.startsWith('codicon-') ? codiconClass : `codicon-${codiconClass}`;
  const IconComponent = CODICON_COMPONENT_MAP[key];
  if (!IconComponent) {
    return <HelpIcon size={size} {...props} />;
  }
  return <IconComponent size={size} {...props} />;
}

export default Icons;
