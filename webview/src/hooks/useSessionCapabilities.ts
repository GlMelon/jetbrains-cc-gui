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
        setLoading(false);
        setError(true);
        return;
      }
      if (!currentSessionId || parsed.sessionId !== currentSessionId) return;
      if (currentProvider && parsed.provider !== currentProvider) return;
      setData(parsed);
      setLoading(false);
      setError(false);
    });
  }, [currentProvider, currentSessionId]);

  const request = useCallback(() => {
    setLoading(true);
    setError(false);
    if (!sendAction(UPSTREAM.GET_SESSION_CAPABILITIES)) {
      setLoading(false);
      setError(true);
    }
  }, []);

  useEffect(() => {
    if (!currentSessionId) return;
    request();
  }, [currentSessionId, currentProvider, request]);

  return { data, loading, error, request };
}
