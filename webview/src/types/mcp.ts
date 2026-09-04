/**
 * MCP (Model Context Protocol) type definitions
 *
 * MCP is Anthropic's standard protocol for AI models to communicate with external tools and data sources.
 *
 * Two configuration sources are supported:
 * 1. cc-switch format: ~/.cc-switch/config.json (primary)
 * 2. Claude native format: ~/.claude.json (compatible)
 *
 * 协议词表(transport/status)SSOT 在 Java enum(McpTransportType/McpServerStatus),
 * 经 generate-protocol-types.mjs 生成到 ../generated/protocol,此处仅引用。
 */

import type { McpServerStatus, McpTransport } from '../generated/protocol';

/**
 * MCP server connection specification
 * Supports three connection types: stdio, http, sse
 */
export interface McpServerSpec {
  /** Connection type, defaults to stdio */
  type?: McpTransport;

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
 * MCP server full configuration
 */
export interface McpServer {
  /** Unique identifier (key in config file) */
  id: string;
  /** Display name */
  name?: string;
  /** Server connection specification */
  server: McpServerSpec;
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
 * MCP server connection status info (from Claude SDK)
 */
export interface McpServerStatusInfo {
  /** Server name */
  name: string;
  /** Connection status(词表 SSOT:Java McpServerStatus;needs-auth 为无生产者的 SDK 时代幽灵值,已删) */
  status: McpServerStatus;
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
