import test from 'node:test';
import assert from 'node:assert/strict';

import { __testing } from '../../../services/claude/persistent-query-service.js';

/**
 * Create a Promise that can be manually resolved.
 * @returns {{ promise: Promise, resolve: Function, reject: Function }}
 */
function createDeferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/**
 * Mock SDK query runtime helper: make next() block (like the real SDK while
 * idle) and let close() release the pending reader so the perpetual reader
 * loop can exit cleanly.
 *
 * Returning done:true immediately from next() is unrealistic: it makes the
 * perpetual reader fall through to its `finally` and dispose the freshly
 * created runtime before acquireRuntime()'s ownership check runs — which does
 * not reflect production behavior and makes acquireRuntime throw
 * "Runtime is closed".
 */
function createBlockingNext(closeRef) {
  return {
    _resolveNext: null,
    next() {
      if (this._closed) return Promise.resolve({ done: true, value: undefined });
      return new Promise((resolve) => {
        this._resolveNext = resolve;
      });
    },
    release() {
      if (this._resolveNext) {
        const resolve = this._resolveNext;
        this._resolveNext = null;
        resolve({ done: true, value: undefined });
      }
    },
    markClosed() {
      this._closed = true;
      this.release();
    }
  };
}

function createQueryFactory() {
  const runtimes = [];
  return {
    runtimes,
    queryFn({ prompt, options }) {
      const reader = createBlockingNext();
      const runtime = {
        prompt,
        options,
        closed: false,
        setPermissionMode: async () => {},
        setModel: async () => {},
        setMaxThinkingTokens: async () => {},
        close() {
          this.closed = true;
          reader.markClosed();
        },
        next() {
          return reader.next();
        }
      };
      runtimes.push(runtime);
      return runtime;
    }
  };
}

function createModelTrackingQueryFactory() {
  const runtimes = [];
  return {
    runtimes,
    queryFn({ prompt, options }) {
      const reader = createBlockingNext();
      const runtime = {
        prompt,
        options,
        closed: false,
        setModelCalls: [],
        setPermissionMode: async () => {},
        setModel: async (model) => {
          runtime.setModelCalls.push(model || null);
          runtime.currentModel = model || null;
        },
        setMaxThinkingTokens: async () => {},
        close() {
          this.closed = true;
          reader.markClosed();
        },
        next() {
          return reader.next();
        },
      };
      runtimes.push(runtime);
      return runtime;
    },
  };
}

/**
 * Create a query factory that returns next() results in sequence.
 * @param {Array<(() => Promise<{done: boolean, value?: any}>) | {done: boolean, value?: any}>} steps
 */
function createSequencedQueryFactory(steps) {
  const runtimes = [];
  return {
    runtimes,
    queryFn({ prompt, options }) {
      let index = 0;
      const runtime = {
        prompt,
        options,
        closed: false,
        setPermissionMode: async () => {},
        setModel: async () => {},
        setMaxThinkingTokens: async () => {},
        close() {
          this.closed = true;
        },
        async next() {
          const step = steps[index++];
          if (!step) {
            return { done: false, value: { type: 'result', is_error: false } };
          }
          return typeof step === 'function' ? await step() : step;
        }
      };
      runtimes.push(runtime);
      return runtime;
    }
  };
}

test.beforeEach(async () => {
  await __testing.resetState();
});

test.after(async () => {
  await __testing.resetState();
});

test('reasoningEffort is passed as SDK effort and disables fixed thinking tokens', async () => {
  const context = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-effort',
    cwd: process.cwd(),
    message: 'use adaptive effort',
    reasoningEffort: 'xhigh'
  }, false);

  assert.equal(context.options.effort, 'xhigh');
  assert.equal(Object.hasOwn(context.options, 'maxThinkingTokens'), false);
  assert.equal(context.maxThinkingTokens, undefined);
  assert.match(context.runtimeSignature, /"effort":"xhigh"/);
});

test('fixed thinking tokens remain configured when no reasoningEffort is provided', async () => {
  const context = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-thinking',
    cwd: process.cwd(),
    message: 'use default thinking config'
  }, false);

  assert.equal(context.options.effort, undefined);
  assert.equal(context.options.maxThinkingTokens, 10000);
  assert.equal(context.maxThinkingTokens, 10000);
});

test('anonymous runtime is isolated by runtimeSessionEpoch', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const firstContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-1',
    cwd: process.cwd(),
    message: 'hello'
  }, false);

  const runtime1 = await __testing.acquireRuntime(firstContext);
  const runtime1Again = await __testing.acquireRuntime(firstContext);
  assert.equal(runtime1, runtime1Again);
  assert.equal(factory.runtimes.length, 1);

  const secondContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-2',
    cwd: process.cwd(),
    message: 'hello again'
  }, false);

  const runtime2 = await __testing.acquireRuntime(secondContext);
  assert.notEqual(runtime1, runtime2);
  assert.equal(factory.runtimes.length, 2);
});

test('same-tab new-session isolation matches fresh runtime isolation expectations', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const firstContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-a',
    cwd: process.cwd(),
    message: 'first turn'
  }, false);
  const runtimeA = await __testing.acquireRuntime(firstContext);

  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-a' });

  const secondContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-b',
    cwd: process.cwd(),
    message: 'new session turn'
  }, false);
  const runtimeB = await __testing.acquireRuntime(secondContext);

  assert.notEqual(runtimeA, runtimeB);
  assert.equal(factory.runtimes.length, 2);
  assert.equal(__testing.getSnapshot().anonymousRuntimeCount, 1);
});

test('resetRuntimePersistent disposes active turn runtime for interrupted old epoch before next first send', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const oldContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-old',
    cwd: process.cwd(),
    message: 'streaming turn'
  }, false);
  const oldRuntime = await __testing.acquireRuntime(oldContext);
  __testing.setActiveTurnRuntime(oldRuntime);

  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-old' });

  const nextContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-new',
    cwd: process.cwd(),
    message: 'first send after interrupt'
  }, false);
  const nextRuntime = await __testing.acquireRuntime(nextContext);

  assert.equal(oldRuntime.closed, true);
  assert.notEqual(oldRuntime, nextRuntime);
  assert.equal(__testing.getSnapshot().activeTurnEpoch, null);
});

test('restore-history continuation keeps runtime bound to restored session after reset of prior epoch', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const oldAnonymousContext = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-stale',
    cwd: process.cwd(),
    message: 'stale anonymous'
  }, false);
  await __testing.acquireRuntime(oldAnonymousContext);
  await __testing.resetRuntimePersistent({ runtimeSessionEpoch: 'epoch-stale' });

  const restoredContext = await __testing.buildRequestContext({
    sessionId: 'hist-restore',
    runtimeSessionEpoch: 'epoch-restore',
    cwd: process.cwd(),
    message: 'restored continuation'
  }, false);
  const restoredRuntime = await __testing.acquireRuntime(restoredContext);
  const restoredRuntimeAgain = await __testing.acquireRuntime(restoredContext);

  assert.equal(restoredRuntime, restoredRuntimeAgain);
  assert.equal(__testing.getRuntimeForSession('hist-restore'), restoredRuntime);
});

test('session runtime switches when actual model changes within same SDK role bucket', async () => {
  const factory = createModelTrackingQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const firstContext = await __testing.buildRequestContext({
    sessionId: 'same-role-model-switch',
    runtimeSessionEpoch: 'epoch-model-switch',
    cwd: process.cwd(),
    message: 'first',
    model: 'glm-5.2',
    actualModel: 'glm-5.2',
  }, false, { settings: { env: {} } });
  const runtime = await __testing.acquireRuntime(firstContext);

  const secondContext = await __testing.buildRequestContext({
    sessionId: 'same-role-model-switch',
    runtimeSessionEpoch: 'epoch-model-switch',
    cwd: process.cwd(),
    message: 'second',
    model: 'mimo-v2.5',
    actualModel: 'mimo-v2.5',
  }, false, { settings: { env: {} } });
  const reusedRuntime = await __testing.acquireRuntime(secondContext);

  assert.equal(reusedRuntime, runtime);
  assert.deepEqual(factory.runtimes[0].setModelCalls, ['mimo-v2.5']);
  assert.equal(reusedRuntime.currentModel, 'sonnet');
  assert.equal(reusedRuntime.currentResolvedModel, 'mimo-v2.5');
});

test('active session runtime is not disposed by idle cleanup while a turn is executing', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-active',
    runtimeSessionEpoch: 'epoch-active',
    cwd: process.cwd(),
    message: 'long running turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (31 * 60 * 1000);

  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.cleanupSessionRuntimes();

  assert.equal(runtime.closed, false);
  assert.equal(__testing.getRuntimeForSession('session-active'), runtime);

  nextDeferred.resolve({ done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } } });
  await turnPromise;
});

test('idle session runtime is still disposed by idle cleanup', async () => {
  const factory = createQueryFactory();
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-idle',
    runtimeSessionEpoch: 'epoch-idle',
    cwd: process.cwd(),
    message: 'idle turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (31 * 60 * 1000);

  await __testing.cleanupSessionRuntimes();

  assert.equal(runtime.closed, true);
  assert.equal(__testing.getRuntimeForSession('session-idle'), null);
});

test('active anonymous runtime is not disposed by idle cleanup while a turn is executing', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: '',
    runtimeSessionEpoch: 'epoch-anon-active',
    cwd: process.cwd(),
    message: 'anonymous long running turn'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = Date.now() - (11 * 60 * 1000);

  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.cleanupAnonymousRuntimes();

  assert.equal(runtime.closed, false);
  assert.equal(__testing.getSnapshot().anonymousRuntimeCount, 1);

  nextDeferred.resolve({ done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'done' }] } } });
  await turnPromise;
});

test('executeTurn refreshes lastUsedAt while processing query events', async () => {
  const factory = createSequencedQueryFactory([
    { done: false, value: { type: 'assistant', message: { content: [{ type: 'text', text: 'partial' }] } } },
    { done: false, value: { type: 'result', is_error: false } }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-refresh',
    runtimeSessionEpoch: 'epoch-refresh',
    cwd: process.cwd(),
    message: 'refresh lastUsedAt'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  runtime.lastUsedAt = 1;

  await __testing.executeTurn(runtime, context);

  assert.ok(runtime.lastUsedAt > 1);
});

test('abortCurrentTurn still disposes an active runtime explicitly', async () => {
  const nextDeferred = createDeferred();
  const enteredDeferred = createDeferred();
  const factory = createSequencedQueryFactory([
    async () => {
      enteredDeferred.resolve();
      return nextDeferred.promise;
    }
  ]);
  __testing.setQueryFn(factory.queryFn);

  const context = await __testing.buildRequestContext({
    sessionId: 'session-abort',
    runtimeSessionEpoch: 'epoch-abort',
    cwd: process.cwd(),
    message: 'abort me'
  }, false);
  const runtime = await __testing.acquireRuntime(context);
  const turnPromise = __testing.executeTurn(runtime, context);
  await enteredDeferred.promise;

  await __testing.abortCurrentTurn();
  nextDeferred.reject(new Error('runtime terminated'));

  // abortCurrentTurn() breaks the active turn via turnSink.fail('Turn aborted'),
  // which executeTurn re-throws. The authoritative assertion below is that the
  // runtime is explicitly disposed; the reject here only confirms the turn ended.
  await assert.rejects(turnPromise, /Turn aborted|Runtime disposed|runtime terminated/);
  assert.equal(runtime.closed, true);
});
