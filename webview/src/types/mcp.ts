/**
 * MCP (Model Context Protocol) type definitions
 *
 * MCP is Anthropic's standard protocol for AI models to communicate with external tools and data sources.
 *
 * Two configuration sources are supported:
 * 1. cc-switch format: ~/.cc-switch/config.json (primary)
 * 2. Claude native format: ~/.claude.json (compatible)
 */

/**
 * MCP server connection specification
 * Supports three connection types: stdio, http, sse
 */
export interface McpServerSpec {
  /** Connection type, defaults to stdio */
  type?: 'stdio' | 'http' | 'sse';

  // stdio type fields
  /** Command to execute (required for stdio type) */
  command?: string;
  /** Command arguments */
  args?: string[];
  /** Environment variables */
  env?: Record<string, string>;
  /** Working directory */
  cwd?: string;

  // http/sse type fields
  /** Server URL (required for http/sse type) */
  url?: string;
  /** Request headers */
  headers?: Record<string, string>;

  /** Allow extension fields */
  [key: string]: unknown;
}

/**
 * MCP app enablement status (cc-switch v3.7.0 format)
 * Indicates which clients the server is applied to
 */
interface McpApps {
  claude: boolean;
  codex: boolean;
  gemini: boolean;
}

/**
 * MCP server full configuration
 */
export interface McpServer {
  /** Unique identifier (key in config file) */
  id: string;
  /** Display name */
  name?: string;
  /** Server connection specification */
  server: McpServerSpec;
  /** App enablement status (cc-switch format) */
  apps?: McpApps;
  /** Description */
  description?: string;
  /** Tags */
  tags?: string[];
  /** Homepage link */
  homepage?: string;
  /** Documentation link */
  docs?: string;
  /** Whether enabled (legacy format compatibility) */
  enabled?: boolean;
  /** Allow extension fields */
  [key: string]: unknown;
}

/**
 * MCP preset configuration
 */
export interface McpPreset {
  id: string;
  name: string;
  description?: string;
  tags?: string[];
  server: McpServerSpec;
  homepage?: string;
  docs?: string;
}



/**
 * MCP server connection status info (from Claude SDK)
 */
export interface McpServerStatusInfo {
  /** Server name */
  name: string;
  /** Connection status */
  status: 'connected' | 'failed' | 'needs-auth' | 'pending' | 'disabled';
  /** Server info (available on successful connection) */
  serverInfo?: {
    name: string;
    version: string;
  };
  /** Error message (available on connection failure) */
  error?: string;
}

/**
 * MCP connection log entry
 */
export interface McpLogEntry {
  /** Unique identifier */
  id: string;
  /** Timestamp */
  timestamp: Date;
  /** Server name */
  serverName: string;
  /** Log level */
  level: 'info' | 'warn' | 'error' | 'success';
  /** Log message */
  message: string;
}



/**
 * Response for an external MCP configuration import (e.g. GitHub Copilot format).
 * The servers are already mapped to internal entries by the Java backend.
 */
export interface McpImportPreviewResponse {
  servers: McpServer[];
  error?: string;
}
