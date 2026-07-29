// @ts-check
/**
 * SDK Loader - Dynamically loads optional AI SDKs
 *
 * Supports loading SDKs from the user directory ~/.codemoss/dependencies/
 * This allows users to install SDKs on demand rather than bundling them with the plugin
 */

import { existsSync, readFileSync } from 'fs';
import { join } from 'path';
import { pathToFileURL } from 'url';
import { getRealHomeDir, getCodemossDir } from './path-utils.js';

/**
 * SDK 定义项(与 Java DependencyManager.SdkDefinition 对齐)。
 * @typedef {{ id: string; npmPackage: string }} SdkDefinition
 */

/**
 * 已加载的 SDK 模块(dynamic import 结果)。
 * 各 SDK 导出形态不同,以键值集合表示;具体导出由调用方按需断言。
 * @typedef {Record<string, any>} SdkModule
 */

// Base path for dependencies directory - uses the shared path utility
const DEPS_BASE = join(getCodemossDir(), 'dependencies');

/** SDK 模块缓存(按 provider 键)。 @type {Map<string, SdkModule>} */
const sdkCache = new Map();
/** 进行中的加载 Promise 缓存,避免同一 SDK 并发重复加载。 @type {Map<string, Promise<SdkModule>>} */
const loadingPromises = new Map();

/**
 * SDK 定义(kept in sync with DependencyManager.SdkDefinition)。
 * @satisfies {Readonly<{ CLAUDE: SdkDefinition; CODEX: SdkDefinition; OPENCODE: SdkDefinition }>}
 */
export const SDK_DEFINITIONS = {
    CLAUDE: {
        id: 'claude-sdk',
        npmPackage: '@anthropic-ai/claude-agent-sdk'
    },
    CODEX: {
        id: 'codex-sdk',
        npmPackage: '@openai/codex-sdk'
    },
    // §15.6 B12:OpenCode SDK 定义,与 Java SdkDefinition.OPENCODE_SDK 对齐。
    OPENCODE: {
        id: 'opencode-sdk',
        npmPackage: '@opencode-ai/sdk'
    }
};

/**
 * 按 sdkId 拼接该 SDK 的依赖根目录。
 * @param {string} sdkId SDK 标识(如 'claude-sdk')
 * @returns {string}
 */
function getSdkRootDir(sdkId) {
    return join(DEPS_BASE, sdkId);
}

/**
 * 在 SDK 根目录下定位 npm 包目录(node_modules/<pkg>)。
 * @param {string} sdkRootDir SDK 根目录
 * @param {string} pkgName    npm 包名(可含 scope,如 '@openai/codex-sdk')
 * @returns {string}
 */
function getPackageDirFromRoot(sdkRootDir, pkgName) {
    // pkgName like: "@anthropic-ai/claude-agent-sdk" or "@openai/codex-sdk"
    // Logic kept consistent with DependencyManager.getPackageDir()
    const parts = pkgName.split('/');
    return join(sdkRootDir, 'node_modules', ...parts);
}

/**
 * 从 package.json 的 exports 字段挑选目标入口(优先指定 condition,其次 default)。
 * exports 形态多样(string / { '.': {...} } / { import, require, default }),统一放宽为 any 处理。
 * @param {any} exportsField package.json 的 exports 字段
 * @param {string} condition  优先匹配的条件名(如 'import')
 * @returns {string | null}
 */
function pickExportTarget(exportsField, condition) {
    if (!exportsField) return null;
    if (typeof exportsField === 'string') return exportsField;

    // exports: { ".": {...} } or exports: { import: "...", require: "...", default: "..." }
    const root = exportsField['.'] ?? exportsField;
    if (typeof root === 'string') return root;

    if (root && typeof root === 'object') {
        if (typeof root[condition] === 'string') return root[condition];
        if (typeof root.default === 'string') return root.default;
    }

    return null;
}

/**
 * 从包目录解析具体入口文件(Node ESM 不能直接 import 目录)。
 * @param {string} packageDir 包目录
 * @returns {string | null}
 */
function resolveEntryFileFromPackageDir(packageDir) {
    // Node ESM does not support importing a directory path directly.
    // We must resolve to a concrete file (e.g., sdk.mjs / index.js / export target).
    const pkgJsonPath = join(packageDir, 'package.json');
    if (existsSync(pkgJsonPath)) {
        try {
            const pkg = JSON.parse(readFileSync(pkgJsonPath, 'utf8'));

            const exportTarget =
                pickExportTarget(pkg.exports, 'import') ??
                pickExportTarget(pkg.exports, 'default');

            const candidate =
                exportTarget ??
                (typeof pkg.module === 'string' ? pkg.module : null) ??
                (typeof pkg.main === 'string' ? pkg.main : null);

            if (candidate && typeof candidate === 'string') {
                return join(packageDir, candidate);
            }
        } catch {
            // ignore and fall through to heuristic
        }
    }

    // Heuristics (covers @anthropic-ai/claude-agent-sdk which has sdk.mjs)
    const heuristicCandidates = ['sdk.mjs', 'index.mjs', 'index.js', 'dist/index.js', 'dist/index.mjs'];
    for (const file of heuristicCandidates) {
        const full = join(packageDir, file);
        if (existsSync(full)) return full;
    }

    return null;
}

/**
 * 解析外部 npm 包入口的 file:// URL。
 * @param {string} pkgName     npm 包名
 * @param {string} sdkRootDir  SDK 根目录
 * @returns {string} file:// URL
 * @throws {Error} 解析不到入口文件时抛错
 */
function resolveExternalPackageUrl(pkgName, sdkRootDir) {
    // Resolve from package directory (works for external node_modules without touching Node's default resolver)
    const packageDir = getPackageDirFromRoot(sdkRootDir, pkgName);
    const entry = resolveEntryFileFromPackageDir(packageDir);
    if (!entry) {
        throw new Error(`Unable to resolve entry file for ${pkgName} from ${packageDir}`);
    }
    return pathToFileURL(entry).href;
}

/**
 * Check whether the Claude Code SDK is available
 * Logic kept consistent with DependencyManager.isInstalled("claude")
 * @returns {boolean}
 */
export function isClaudeSdkAvailable() {
    const sdkId = 'claude-sdk';
    const npmPackage = '@anthropic-ai/claude-agent-sdk';
    const sdkPath = getPackageDirFromRoot(getSdkRootDir(sdkId), npmPackage);
    const exists = existsSync(sdkPath);
    console.error('[sdk-loader] isClaudeSdkAvailable:', {
        path: sdkPath,
        exists: exists,
        depsBase: DEPS_BASE
    });
    return exists;
}

/**
 * Check whether the Codex SDK is available
 * Logic kept consistent with DependencyManager.isInstalled("codex")
 * @returns {boolean}
 */
export function isCodexSdkAvailable() {
    const sdkId = 'codex-sdk';
    const npmPackage = '@openai/codex-sdk';
    const sdkPath = getPackageDirFromRoot(getSdkRootDir(sdkId), npmPackage);
    const exists = existsSync(sdkPath);
    console.error('[sdk-loader] isCodexSdkAvailable:', {
        path: sdkPath,
        exists: exists
    });
    return exists;
}

/**
 * §15.6 B12:Check whether the OpenCode SDK is available
 * Logic kept consistent with DependencyManager.isInstalled("opencode") / SDK_DEFINITIONS.OPENCODE
 * @returns {boolean}
 */
export function isOpencodeSdkAvailable() {
    const sdkId = SDK_DEFINITIONS.OPENCODE.id;
    const npmPackage = SDK_DEFINITIONS.OPENCODE.npmPackage;
    const sdkPath = getPackageDirFromRoot(getSdkRootDir(sdkId), npmPackage);
    const exists = existsSync(sdkPath);
    console.error('[sdk-loader] isOpencodeSdkAvailable:', {
        path: sdkPath,
        exists: exists
    });
    return exists;
}

/**
 * Dynamically load the Claude SDK
 * @returns {Promise<SdkModule>}
 * @throws {Error} If the SDK is not installed
 */
export async function loadClaudeSdk() {
    console.error('[DIAG-SDK] loadClaudeSdk() called');

    // Return the cached SDK if available
    if (sdkCache.has('claude')) {
        console.error('[DIAG-SDK] Returning cached SDK');
        return /** @type {SdkModule} */ (sdkCache.get('claude'));
    }

    // If a load is already in progress, return the same promise to prevent duplicate loading
    if (loadingPromises.has('claude')) {
        console.error('[DIAG-SDK] SDK loading in progress, returning existing promise');
        return /** @type {Promise<SdkModule>} */ (loadingPromises.get('claude'));
    }

    const sdkRootDir = getSdkRootDir('claude-sdk');
    const sdkPath = getPackageDirFromRoot(sdkRootDir, '@anthropic-ai/claude-agent-sdk');
    console.error('[DIAG-SDK] SDK path:', sdkPath);
    console.error('[DIAG-SDK] SDK path exists:', existsSync(sdkPath));

    if (!existsSync(sdkPath)) {
        console.error('[DIAG-SDK] SDK not installed at path');
        throw new Error('SDK_NOT_INSTALLED:claude');
    }

    // Create and cache the loading promise
    const loadPromise = (async () => {
        try {
            console.error('[DIAG-SDK] SDK root dir:', sdkRootDir);

            // Node ESM does not support import(directory); must resolve to a concrete file (e.g. sdk.mjs)
            const resolvedUrl = resolveExternalPackageUrl('@anthropic-ai/claude-agent-sdk', sdkRootDir);
            console.error('[DIAG-SDK] Resolved URL:', resolvedUrl);

            console.error('[DIAG-SDK] Starting dynamic import...');
            const sdk = await import(resolvedUrl);
            console.error('[DIAG-SDK] SDK imported successfully, exports:', Object.keys(sdk));

            sdkCache.set('claude', sdk);
            return sdk;
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            console.error('[DIAG-SDK] SDK import failed:', msg);
            const pkgDir = getPackageDirFromRoot(sdkRootDir, '@anthropic-ai/claude-agent-sdk');
            const hintFile = join(pkgDir, 'sdk.mjs');
            const hint = existsSync(hintFile) ? ` Did you mean to import ${hintFile}?` : '';
            throw new Error(`Failed to load Claude SDK: ${msg}${hint}`);
        } finally {
            // Clear the promise cache once loading is complete
            loadingPromises.delete('claude');
        }
    })();

    loadingPromises.set('claude', loadPromise);
    return loadPromise;
}

/**
 * Dynamically load the Codex SDK
 * @returns {Promise<SdkModule>}
 * @throws {Error} If the SDK is not installed
 */
export async function loadCodexSdk() {
    // Return the cached SDK if available
    if (sdkCache.has('codex')) {
        return /** @type {SdkModule} */ (sdkCache.get('codex'));
    }

    // If a load is already in progress, return the same promise to prevent duplicate loading
    if (loadingPromises.has('codex')) {
        return /** @type {Promise<SdkModule>} */ (loadingPromises.get('codex'));
    }

    const sdkRootDir = getSdkRootDir('codex-sdk');
    const sdkPath = getPackageDirFromRoot(sdkRootDir, '@openai/codex-sdk');

    if (!existsSync(sdkPath)) {
        throw new Error('SDK_NOT_INSTALLED:codex');
    }

    // Create and cache the loading promise
    const loadPromise = (async () => {
        try {
            const resolvedUrl = resolveExternalPackageUrl('@openai/codex-sdk', sdkRootDir);
            const sdk = await import(resolvedUrl);

            sdkCache.set('codex', sdk);
            return sdk;
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            throw new Error(`Failed to load Codex SDK: ${msg}`);
        } finally {
            loadingPromises.delete('codex');
        }
    })();

    loadingPromises.set('codex', loadPromise);
    return loadPromise;
}

/**
 * §15.6 B12:Dynamically load the OpenCode SDK.
 * 同构于 loadCodexSdk:缓存键 'opencode',错误码 SDK_NOT_INSTALLED:opencode。
 * @returns {Promise<SdkModule>}
 * @throws {Error} If the SDK is not installed
 */
export async function loadOpencodeSdk() {
    // Return the cached SDK if available
    if (sdkCache.has('opencode')) {
        return /** @type {SdkModule} */ (sdkCache.get('opencode'));
    }

    // If a load is already in progress, return the same promise to prevent duplicate loading
    if (loadingPromises.has('opencode')) {
        return /** @type {Promise<SdkModule>} */ (loadingPromises.get('opencode'));
    }

    const sdkRootDir = getSdkRootDir(SDK_DEFINITIONS.OPENCODE.id);
    const sdkPath = getPackageDirFromRoot(sdkRootDir, SDK_DEFINITIONS.OPENCODE.npmPackage);

    if (!existsSync(sdkPath)) {
        throw new Error('SDK_NOT_INSTALLED:opencode');
    }

    // Create and cache the loading promise
    const loadPromise = (async () => {
        try {
            const resolvedUrl = resolveExternalPackageUrl(SDK_DEFINITIONS.OPENCODE.npmPackage, sdkRootDir);
            const sdk = await import(resolvedUrl);

            sdkCache.set('opencode', sdk);
            return sdk;
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            throw new Error(`Failed to load OpenCode SDK: ${msg}`);
        } finally {
            loadingPromises.delete('opencode');
        }
    })();

    loadingPromises.set('opencode', loadPromise);
    return loadPromise;
}

/**
 * Load the base Anthropic SDK (used as an API fallback)
 * @returns {Promise<SdkModule>}
 * @throws {Error} If the SDK is not installed
 */
export async function loadAnthropicSdk() {
    // Return the cached SDK if available
    if (sdkCache.has('anthropic')) {
        return /** @type {SdkModule} */ (sdkCache.get('anthropic'));
    }

    // If a load is already in progress, return the same promise to prevent duplicate loading
    if (loadingPromises.has('anthropic')) {
        return /** @type {Promise<SdkModule>} */ (loadingPromises.get('anthropic'));
    }

    const sdkRootDir = getSdkRootDir('claude-sdk');
    const sdkPath = join(sdkRootDir, 'node_modules', '@anthropic-ai', 'sdk');

    if (!existsSync(sdkPath)) {
        throw new Error('SDK_NOT_INSTALLED:anthropic');
    }

    // Create and cache the loading promise
    const loadPromise = (async () => {
        try {
            const resolvedUrl = resolveExternalPackageUrl('@anthropic-ai/sdk', sdkRootDir);
            const sdk = await import(resolvedUrl);

            sdkCache.set('anthropic', sdk);
            return sdk;
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            throw new Error(`Failed to load Anthropic SDK: ${msg}`);
        } finally {
            loadingPromises.delete('anthropic');
        }
    })();

    loadingPromises.set('anthropic', loadPromise);
    return loadPromise;
}

/**
 * Load the Bedrock SDK
 * @returns {Promise<SdkModule>}
 * @throws {Error} If the SDK is not installed
 */
export async function loadBedrockSdk() {
    // Return the cached SDK if available
    if (sdkCache.has('bedrock')) {
        return /** @type {SdkModule} */ (sdkCache.get('bedrock'));
    }

    // If a load is already in progress, return the same promise to prevent duplicate loading
    if (loadingPromises.has('bedrock')) {
        return /** @type {Promise<SdkModule>} */ (loadingPromises.get('bedrock'));
    }

    const sdkRootDir = getSdkRootDir('claude-sdk');
    const sdkPath = join(sdkRootDir, 'node_modules', '@anthropic-ai', 'bedrock-sdk');

    if (!existsSync(sdkPath)) {
        throw new Error('SDK_NOT_INSTALLED:bedrock');
    }

    // Create and cache the loading promise
    const loadPromise = (async () => {
        try {
            const resolvedUrl = resolveExternalPackageUrl('@anthropic-ai/bedrock-sdk', sdkRootDir);
            const sdk = await import(resolvedUrl);

            sdkCache.set('bedrock', sdk);
            return sdk;
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            throw new Error(`Failed to load Bedrock SDK: ${msg}`);
        } finally {
            loadingPromises.delete('bedrock');
        }
    })();

    loadingPromises.set('bedrock', loadPromise);
    return loadPromise;
}

/**
 * Get the installation status of all SDKs
 * @returns {{ claude: { installed: boolean; path: string }; codex: { installed: boolean; path: string }; opencode: { installed: boolean; path: string } }}
 */
export function getSdkStatus() {
    // Uses the same path resolution logic as DependencyManager
    const claudeInstalled = isClaudeSdkAvailable();
    const codexInstalled = isCodexSdkAvailable();
    const opencodeInstalled = isOpencodeSdkAvailable();

    return {
        claude: {
            installed: claudeInstalled,
            path: getPackageDirFromRoot(getSdkRootDir('claude-sdk'), '@anthropic-ai/claude-agent-sdk')
        },
        codex: {
            installed: codexInstalled,
            path: getPackageDirFromRoot(getSdkRootDir('codex-sdk'), '@openai/codex-sdk')
        },
        // §15.6 B12:OpenCode SDK 状态字段,与 claude/codex 对称。
        opencode: {
            installed: opencodeInstalled,
            path: getPackageDirFromRoot(
                getSdkRootDir(SDK_DEFINITIONS.OPENCODE.id),
                SDK_DEFINITIONS.OPENCODE.npmPackage
            )
        }
    };
}

/**
 * Clear the SDK cache
 * Should be called after an SDK is reinstalled
 * @returns {void}
 */
export function clearSdkCache() {
    sdkCache.clear();
}

/**
 * Verify that the SDK is installed, throwing a user-friendly error if not
 * @param {string} provider - 'claude', 'codex' or 'opencode'
 * @returns {void}
 * @throws {Error} If the SDK is not installed
 */
export function requireSdk(provider) {
    if (provider === 'claude' && !isClaudeSdkAvailable()) {
        const error = /** @type {Error & { code?: string; provider?: string }} */ (new Error('Claude Code SDK not installed. Please install via Settings > Dependencies.'));
        error.code = 'SDK_NOT_INSTALLED';
        error.provider = 'claude';
        throw error;
    }

    if (provider === 'codex' && !isCodexSdkAvailable()) {
        const error = /** @type {Error & { code?: string; provider?: string }} */ (new Error('Codex SDK not installed. Please install via Settings > Dependencies.'));
        error.code = 'SDK_NOT_INSTALLED';
        error.provider = 'codex';
        throw error;
    }

    // §15.6 B12:OpenCode SDK 安装校验,与 claude/codex 对称。
    if (provider === 'opencode' && !isOpencodeSdkAvailable()) {
        const error = /** @type {Error & { code?: string; provider?: string }} */ (new Error('OpenCode SDK not installed. Please install via Settings > Dependencies.'));
        error.code = 'SDK_NOT_INSTALLED';
        error.provider = 'opencode';
        throw error;
    }
}
