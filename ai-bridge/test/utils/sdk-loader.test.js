import test from 'node:test';
import assert from 'node:assert/strict';

import {
    SDK_DEFINITIONS,
    isClaudeSdkAvailable,
    isCodexSdkAvailable,
    isOpencodeSdkAvailable,
    loadOpencodeSdk,
    getSdkStatus,
    requireSdk,
    clearSdkCache
} from '../../utils/sdk-loader.js';

/**
 * §15.6 B12:sdk-loader.js 对 OpenCode SDK 的对称补齐。
 * 与 Claude/Codex 同构:SDK_DEFINITIONS.OPENCODE + isOpencodeSdkAvailable + loadOpencodeSdk +
 * getSdkStatus.opencode + requireSdk('opencode')。
 * 注:测试环境 ~/.codemoss/dependencies/opencode-sdk 不存在,断言聚焦结构对称与"未安装"降级语义。
 */

test('SDK_DEFINITIONS includes OPENCODE mirroring SdkDefinition enum', () => {
    assert.ok(SDK_DEFINITIONS.OPENCODE, 'OPENCODE definition must exist');
    assert.equal(SDK_DEFINITIONS.OPENCODE.id, 'opencode-sdk');
    assert.equal(SDK_DEFINITIONS.OPENCODE.npmPackage, '@opencode-ai/sdk');
});

test('isOpencodeSdkAvailable is a function mirroring claude/codex', () => {
    assert.equal(typeof isOpencodeSdkAvailable, 'function');
    assert.equal(typeof isClaudeSdkAvailable, 'function');
    assert.equal(typeof isCodexSdkAvailable, 'function');
    // 未安装路径返回 false(不抛异常)
    assert.equal(typeof isOpencodeSdkAvailable(), 'boolean');
});

test('getSdkStatus exposes opencode field symmetric to claude/codex', () => {
    const status = getSdkStatus();
    assert.ok(status.claude, 'claude field present');
    assert.ok(status.codex, 'codex field present');
    assert.ok(status.opencode, 'opencode field present');
    assert.equal(typeof status.opencode.installed, 'boolean');
    assert.ok(status.opencode.path, 'opencode path resolved');
    assert.ok(
        status.opencode.path.includes('opencode-sdk'),
        'opencode path points to opencode-sdk deps dir: ' + status.opencode.path
    );
});

test('loadOpencodeSdk throws SDK_NOT_INSTALLED:opencode when absent', async () => {
    clearSdkCache();
    if (isOpencodeSdkAvailable()) {
        // 若环境恰好已安装,跳过未安装断言(不应失败)
        return;
    }
    await assert.rejects(
        () => loadOpencodeSdk(),
        (err) => err.message.includes('opencode') && err.message.includes('SDK_NOT_INSTALLED')
    );
});

test('requireSdk(opencode) throws SDK_NOT_INSTALLED when not installed', () => {
    if (isOpencodeSdkAvailable()) {
        return;
    }
    assert.throws(
        () => requireSdk('opencode'),
        (err) => err.code === 'SDK_NOT_INSTALLED' && err.provider === 'opencode'
    );
});

test('requireSdk(opencode) does not throw when installed', () => {
    if (!isOpencodeSdkAvailable()) {
        return;
    }
    // 不应抛
    assert.doesNotThrow(() => requireSdk('opencode'));
});
