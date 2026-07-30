/**
 * SDK dependency type definitions
 *
 * SDK dependency installation path: ~/.codemoss/dependencies/
 * - claude-sdk: Claude SDK (@anthropic-ai/claude-agent-sdk and its dependencies)
 * - codex-sdk: Codex SDK (@openai/codex-sdk)
 *
 * Supported operations:
 * - Install/uninstall SDKs
 * - Check for updates
 * - View installation status
 */

import type { VersionAction } from '../generated/protocol';

/**
 * SDK ID type
 */
export type SdkId = 'claude-sdk' | 'codex-sdk' | 'opencode-sdk';

/**
 * SDK installation status
 */
type SdkInstallStatus = 'installed' | 'not_installed' | 'installing' | 'error';

/**
 * Status information for a single SDK
 */
export interface SdkStatus {
  /** Unique SDK identifier */
  id: SdkId;
  /** SDK display name */
  name: string;
  /** Installation status */
  status: SdkInstallStatus;
  /** Installed version (empty when not installed) */
  installedVersion?: string;
  /** Latest available version */
  latestVersion?: string;
  /** Whether an update is available */
  hasUpdate?: boolean;
  /** Installation path */
  installPath?: string;
  /** Description */
  description?: string;
  /** Last checked time */
  lastChecked?: string;
  /** Error message (when status is error) */
  errorMessage?: string;
}



/**
 * Installation progress information
 */
export interface InstallProgress {
  /** SDK ID */
  sdkId: SdkId;
  /** Log output */
  log: string;
}

/**
 * Installation result
 */
export interface InstallResult {
  /** Whether successful */
  success: boolean;
  /** SDK ID */
  sdkId: SdkId;
  /** Installed version (on success) */
  installedVersion?: string;
  /** Requested version (on success/failure) */
  requestedVersion?: string;
  /** Error message (on failure) */
  error?: string;
  /** Installation logs */
  logs?: string;
}

/**
 * Uninstall result
 */
export interface UninstallResult {
  /** Whether successful */
  success: boolean;
  /** SDK ID */
  sdkId: SdkId;
  /** Error message (on failure) */
  error?: string;
}

/**
 * Update check result
 */
export interface UpdateCheckResult {
  [key: string]: {
    /** SDK ID */
    sdkId: SdkId;
    /** SDK name */
    sdkName: string;
    /** Whether an update is available */
    hasUpdate: boolean;
    /** Current version */
    currentVersion?: string;
    /** Latest version */
    latestVersion?: string;
    /** Error message */
    error?: string;
  };
}

type DependencyVersionSource = 'remote' | 'fallback';

export interface DependencyVersionInfo {
  sdkId: SdkId;
  versions: string[];
  fallbackVersions?: string[];
  source: DependencyVersionSource;
  latestVersion?: string;
  /** A6:后端预计算的「目标版本 → 动作」映射（仅 SDK 已安装时随 versions_loaded 下发） */
  versionActions?: Record<string, VersionAction>;
  error?: string;
}

export interface DependencyVersionResult {
  [key: string]: DependencyVersionInfo;
}

/**
 * Node.js environment status
 */
export interface NodeEnvironmentStatus {
  /** Whether available */
  available: boolean;
  /** Error message */
  error?: string;
}




