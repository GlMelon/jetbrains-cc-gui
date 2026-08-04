import { beforeEach, describe, expect, it, vi } from 'vitest';
import { bridgeHub } from '../../../src/bridge';
import { DOWNSTREAM, UPSTREAM } from '../../../src/generated/protocol';
import { sendAction, subscribeEvent } from '../../../src/bridge/typed';

describe('typed bridge', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
    window.sendToJava = vi.fn();
  });

  it('sends upstream action constants through the Java envelope', () => {
    sendAction(UPSTREAM.GET_MODEL_REGISTRY);

    expect(window.sendToJava).toHaveBeenCalledWith(JSON.stringify({
      type: 'get_model_registry',
      content: '',
    }));
  });

  it('subscribes to downstream event constants without parsing raw payloads', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeEvent(DOWNSTREAM.MODEL_REGISTRY, listener);

    bridgeHub.dispatch('model_registry', '{"items":[]}');
    unsubscribe();
    bridgeHub.dispatch('model_registry', '{"items":[1]}');

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith('{"items":[]}');
  });
});
