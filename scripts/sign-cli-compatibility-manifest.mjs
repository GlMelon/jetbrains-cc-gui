#!/usr/bin/env node
/**
 * 重签 bundled CLI 兼容性 manifest 的 Ed25519 detached 签名。
 *
 * 何时用:每当 src/main/resources/compatibility/cli-compatibility-manifest.json 内容变更,
 *   原 .sig 失效,需用维护者私钥重签(私钥对应 CliCompatibilityManifestRepository.PUBLIC_KEY_BASE64)。
 *
 * 用法(私钥从不落盘、不打印):
 *   # 私钥为 PKCS#8 DER 的 base64(与公钥 X509/SPKI 编码族对称,Java KeyPairGenerator 产出格式):
 *   CLI_COMPAT_PRIVATE_KEY="<base64>" node scripts/sign-cli-compatibility-manifest.mjs
 *   # 或 PEM:
 *   CLI_COMPAT_PRIVATE_KEY="$(cat priv.pem)" node scripts/sign-cli-compatibility-manifest.mjs
 *
 * 安全防呆:脚本从私钥派生公钥,断言其等于内嵌 PUBLIC_KEY_BASE64,不匹配则报错退出(防用错 key)。
 *   签名产物格式 = base64(64 字节 Ed25519 签名)+ 换行,与 Ed25519ManifestSignatureVerifier 解码一致。
 */
import { createPrivateKey, createPublicKey, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

// 必须与 CliCompatibilityManifestRepository.PUBLIC_KEY_BASE64 一致(改之前先核对 Java 常量)。
const EMBEDDED_PUBLIC_KEY_BASE64 = 'MCowBQYDK2VwAyEAUEM7BQrevvNE4oPTrlvnWJcNFgxus9INUVLoPxPvzeQ=';
const MANIFEST_PATH = resolve('src/main/resources/compatibility/cli-compatibility-manifest.json');
const SIG_PATH = resolve('src/main/resources/compatibility/cli-compatibility-manifest.sig');

const keyMaterial = process.env.CLI_COMPAT_PRIVATE_KEY;
if (!keyMaterial || keyMaterial.trim() === '') {
  console.error('错误:请通过环境变量 CLI_COMPAT_PRIVATE_KEY 提供私钥(base64 PKCS#8 DER 或 PEM)。');
  console.error('示例:CLI_COMPAT_PRIVATE_KEY="<base64>" node scripts/sign-cli-compatibility-manifest.mjs');
  process.exit(1);
}

// 接受 PEM 或 base64 DER(PKCS#8)。
let privateKey;
try {
  if (keyMaterial.includes('-----BEGIN')) {
    privateKey = createPrivateKey({ key: keyMaterial, format: 'pem' });
  } else {
    privateKey = createPrivateKey({
      key: Buffer.from(keyMaterial.trim(), 'base64'),
      format: 'der',
      type: 'pkcs8',
    });
  }
} catch (e) {
  console.error('错误:无法解析私钥(既非 PEM 也非 base64 PKCS#8 DER):' + e.message);
  process.exit(1);
}

// 防呆:派生公钥必须等于内嵌公钥,否则签出来的 sig 在 Java 验证器侧验不过。
const derivedPublicB64 = createPublicKey(privateKey)
  .export({ type: 'spki', format: 'der' })
  .toString('base64');
if (derivedPublicB64 !== EMBEDDED_PUBLIC_KEY_BASE64) {
  console.error('错误:私钥派生的公钥与内嵌 PUBLIC_KEY_BASE64 不匹配——你用的不是签名此 manifest 的那个 key。');
  console.error('  内嵌公钥: ' + EMBEDDED_PUBLIC_KEY_BASE64);
  console.error('  派生公钥: ' + derivedPublicB64);
  process.exit(2);
}

const manifestBytes = readFileSync(MANIFEST_PATH);
// Ed25519:sign 的 algorithm 参数传 null(Node 用 key 的类型决定算法)。
const sigBytes = sign(null, manifestBytes, privateKey);
const sigB64 = sigBytes.toString('base64');

writeFileSync(SIG_PATH, sigB64 + '\n', 'ascii');
console.log(`✓ 已签名 ${MANIFEST_PATH}`);
console.log(`  -> ${SIG_PATH} (${sigBytes.length} 字节签名, ${sigB64.length} base64 字符)`);
console.log(`  公钥校验通过(派生公钥 == PUBLIC_KEY_BASE64)。`);
