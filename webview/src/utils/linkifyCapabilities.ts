import { createCallbackChannel } from './createCallbackChannel';

export type LinkifyCapabilities = {
  classNavigationEnabled: boolean;
};

export const DEFAULT_LINKIFY_CAPABILITIES: LinkifyCapabilities = Object.freeze({
  classNavigationEnabled: false,
});

type LinkifyCapabilitiesListener = (capabilities: LinkifyCapabilities) => void;

let currentCapabilities: LinkifyCapabilities = { ...DEFAULT_LINKIFY_CAPABILITIES };

// 创建回调通道
const channel = createCallbackChannel<LinkifyCapabilities>({
  name: 'linkifyCapabilities',
});

function normalizeLinkifyCapabilities(value: unknown): LinkifyCapabilities {
  if (!value || typeof value !== 'object') {
    return { ...DEFAULT_LINKIFY_CAPABILITIES };
  }

  const candidate = value as Partial<LinkifyCapabilities>;
  return {
    classNavigationEnabled: Boolean(candidate.classNavigationEnabled),
  };
}

function cloneCapabilities(capabilities: LinkifyCapabilities): LinkifyCapabilities {
  return { ...capabilities };
}

function areCapabilitiesEqual(
  left: LinkifyCapabilities,
  right: LinkifyCapabilities,
): boolean {
  return left.classNavigationEnabled === right.classNavigationEnabled;
}

export function getLinkifyCapabilities(): LinkifyCapabilities {
  return cloneCapabilities(currentCapabilities);
}

export function setLinkifyCapabilities(value: unknown): LinkifyCapabilities {
  const nextCapabilities = normalizeLinkifyCapabilities(value);
  if (areCapabilitiesEqual(currentCapabilities, nextCapabilities)) {
    return cloneCapabilities(currentCapabilities);
  }

  currentCapabilities = nextCapabilities;
  channel.emit(currentCapabilities);
  return cloneCapabilities(currentCapabilities);
}

export function applyLinkifyCapabilitiesPayload(json: string): LinkifyCapabilities {
  try {
    return setLinkifyCapabilities(JSON.parse(json));
  } catch {
    return setLinkifyCapabilities(DEFAULT_LINKIFY_CAPABILITIES);
  }
}

export function subscribeLinkifyCapabilities(
  listener: LinkifyCapabilitiesListener,
): () => void {
  return channel.subscribe(listener);
}

export function resetLinkifyCapabilities(): void {
  setLinkifyCapabilities(DEFAULT_LINKIFY_CAPABILITIES);
}
