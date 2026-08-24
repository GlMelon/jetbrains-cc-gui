import { useCallback, useEffect, useState } from 'react';
import { sendAction, subscribeEvent } from '../bridge/typed';
import {
  DOWNSTREAM,
  UPSTREAM,
  type SessionCapabilitiesPayloadWire,
  type SessionMcpCapabilityPayloadWire,
  type SessionSkillCapabilityPayloadWire,
} from '../generated/protocol';

export type SessionMcpCapability = SessionMcpCapabilityPayloadWire;
export type SessionSkillCapability = SessionSkillCapabilityPayloadWire;
export type SessionCapabilities = SessionCapabilitiesPayloadWire;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isNullableString(value: unknown): value is string | null {
  return typeof value === 'string' || value === null;
}

function isNullableNumber(value: unknown): value is number | null {
  return typeof value === 'number' || value === null;
}

function isMcpCapability(value: unknown): value is SessionMcpCapability {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    typeof value.provider === 'string' &&
    typeof value.state === 'string' &&
    isNullableString(value.lastError) &&
    isNullableNumber(value.lastSuccessAt) &&
    typeof value.failureCount === 'number' &&
    typeof value.observed === 'boolean'
  );
}

function isSkillCapability(value: unknown): value is SessionSkillCapability {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    typeof value.scope === 'string' &&
    typeof value.state === 'string' &&
    typeof value.observed === 'boolean' &&
    typeof value.source === 'string'
  );
}

/**
 * sendAction 失败时的重试延迟。window.sendToJava 由 onLoadEnd 的 JSQuery 注入,
 * 与 React 挂载/会话恢复存在竞态(settingsBootstrap 对同一竞态已采用相同重试模式);
 * 不重试会把启动窗口期的瞬时不可用固化为 error 状态。
 */
const SEND_RETRY_DELAYS_MS = [100, 200, 400, 800];

function parseCapabilities(payload: unknown): SessionCapabilities | null {
  let value: unknown = payload;
  if (typeof payload === 'string') {
    try {
      value = JSON.parse(payload) as unknown;
    } catch {
      return null;
    }
  }
  if (!isRecord(value) || !Array.isArray(value.mcp) || !Array.isArray(value.skills)) return null;
  if (!value.mcp.every(isMcpCapability) || !value.skills.every(isSkillCapability)) return null;
  if (
    typeof value.sessionId !== 'string' ||
    typeof value.runtimeEpoch !== 'string' ||
    typeof value.provider !== 'string' ||
    typeof value.observedAt !== 'number' ||
    typeof value.mcpAvailable !== 'boolean' ||
    !isNullableString(value.mcpError)
  ) {
    return null;
  }
  return value as unknown as SessionCapabilities;
}

export function useSessionCapabilities(currentSessionId: string | null, currentProvider: string) {
  const [data, setData] = useState<SessionCapabilities | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    setData(null);
    setLoading(false);
    setError(false);
    return subscribeEvent<string>(DOWNSTREAM.SESSION_CAPABILITIES, (payload) => {
      const parsed = parseCapabilities(payload);
      if (!parsed) {
        // 排查痛点:此前静默失败,无法分辨是 payload 有毒字段还是管道问题。
        // 输出原始 payload 便于从 webview console(转发至 idea.log)定位毒字段。
        console.error('[sessionCapabilities] payload rejected by strict validator:', payload);
        setLoading(false);
        setError(true);
        return;
      }
      if (!currentSessionId || parsed.sessionId !== currentSessionId) {
        // 排查诊断:静默 return 会让人误以为「没收到事件」。匹配失败时输出对比,
        // 用于发现 sessionId/provider 双轨体系不同步(记忆:sessionId 按 provider 解耦技术债)。
        console.warn('[sessionCapabilities] sessionId mismatch, ignored:',
          `parsed=${parsed.sessionId} current=${currentSessionId}`);
        return;
      }
      if (currentProvider && parsed.provider !== currentProvider) {
        console.warn('[sessionCapabilities] provider mismatch, ignored:',
          `parsed=${parsed.provider} current=${currentProvider}`);
        return;
      }
      setData(parsed);
      setLoading(false);
      setError(false);
    });
  }, [currentProvider, currentSessionId]);

  const request = useCallback(() => {
    setLoading(true);
    setError(false);
    // sendToJava 未就绪(webview 早期/重载竞态)时按延迟梯度补发;
    // 全部失败才落 error,避免把启动窗口期的瞬时不可用固化为错误。
    const attempt = (index: number) => {
      if (sendAction(UPSTREAM.GET_SESSION_CAPABILITIES)) return;
      if (index < SEND_RETRY_DELAYS_MS.length) {
        setTimeout(() => attempt(index + 1), SEND_RETRY_DELAYS_MS[index]);
        return;
      }
      setLoading(false);
      setError(true);
    };
    attempt(0);
  }, []);

  useEffect(() => {
    if (!currentSessionId) return;
    request();
  }, [currentSessionId, currentProvider, request]);

  return { data, loading, error, request };
}
