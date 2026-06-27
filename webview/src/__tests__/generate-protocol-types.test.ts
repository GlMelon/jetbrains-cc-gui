import { describe, it, expect, vi } from 'vitest';
import { parseEnumSource, parsePayloadFieldSource, generateFromManifest, parseIntConstants } from '../../scripts/generate-protocol-types.mjs';

/**
 * C8 漂移守门测试:parseEnumSource 的 entryPattern(commit 9ee6ebff 起支持单参/多参),
 * 匹配 NAME("value" 并取首参作为协议值(如 ProviderType 的 value,cliCommand,cliCommandWindows 多参)。
 * DRIFT 告警在 loosePattern(全大写名 + 引号)计数 > entryPattern 解析数时触发 ——
 * 捕获非标准格式(如单引号 NAME('value'))或首参非字符串导致的静默漏项。
 *
 * 注:本文件位于 src/__tests__(入库),不在 src/generated(被 .gitignore)。
 * generate-protocol-types.mjs 是入库生成脚本,守门测试必须随源入库。
 */
describe('parseEnumSource — C8 drift guard', () => {
  it('parses single-arg NAME("value") entries correctly', () => {
    const src = `
      public enum X implements ProtocolValue {
        FOO("foo"),
        BAR("bar"),
      }
    `;
    expect(parseEnumSource(src)).toEqual([
      { name: 'FOO', value: 'foo' },
      { name: 'BAR', value: 'bar' },
    ]);
  });

  it('does NOT warn when all entries are single-arg (current UpstreamAction/DownstreamEvent shape)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    parseEnumSource('A("a"),\nB("b"),\nC("c"),');
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('多参 NAME("value","desc") 取首参作为协议值,不漂移不告警(commit 9ee6ebff)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    // C2 多参形态:ProviderType 等枚举含 value,cliCommand,cliCommandWindows
    const src = `
      public enum X implements ProtocolValue {
        FOO("foo"),
        BAR("bar", "bar-desc"),
      }
    `;
    const entries = parseEnumSource(src, 'X.java');
    // entryPattern 匹配 NAME("value"(取首参),多参 BAR 也解析,无漏项
    expect(entries).toEqual([
      { name: 'FOO', value: 'foo' },
      { name: 'BAR', value: 'bar' },
    ]);
    // 多参被正确解析,looseCount == entries.length,无漂移告警
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it("单引号非标准格式 NAME('value') 触发 DRIFT 告警(宽松启发捕获,严格 regex 漏)", () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const src = `
      public enum X implements ProtocolValue {
        FOO("foo"),
        QUX('value'),
      }
    `;
    parseEnumSource(src, 'X.java');
    // entryPattern 只认双引号,QUX('value') 单引号被漏;loosePattern 认引号(含单引号)计数多 1 → 漂移告警
    expect(warnSpy).toHaveBeenCalled();
    expect(warnSpy.mock.calls[0][0]).toContain('DRIFT');
    warnSpy.mockRestore();
  });

  it('returns empty array (no throw) for source with no enum entries', () => {
    // parseEnumSource 本身不抛(由 parseJavaEnumProtocol 在 0 条目时抛)
    expect(parseEnumSource('no enum constants here')).toEqual([]);
  });
});

/**
 * C1 payload 字段声明解析:parsePayloadFieldSource 严格三参 regex 匹配
 * NAME("wireKey","tsType",optional),与 parseEnumSource(单参)正交,不互相污染。
 */
describe('parsePayloadFieldSource — C1 payload field parsing', () => {
  it('解析三参 payload 字段声明(name/wireKey/tsType/optional)', () => {
    const source = `public enum Foo {
    ID("id", "string", false),
    AGE("age", "number", true),
    TAGS("tags", "readonly string[]", false);
}`;
    expect(parsePayloadFieldSource(source)).toEqual([
      { name: 'ID', wireKey: 'id', tsType: 'string', optional: false },
      { name: 'AGE', wireKey: 'age', tsType: 'number', optional: true },
      { name: 'TAGS', wireKey: 'tags', tsType: 'readonly string[]', optional: false },
    ]);
  });

  it('不误匹配单参枚举(UpstreamAction 风格 NAME("value"))', () => {
    const singleArg = `public enum UpstreamAction {
    SEND_MESSAGE("send_message"),
    HEARTBEAT("heartbeat");`;
    expect(parsePayloadFieldSource(singleArg)).toEqual([]);
  });

  it('空源码返回空数组', () => {
    expect(parsePayloadFieldSource('')).toEqual([]);
  });

  it('忽略注释与非枚举行', () => {
    const source = `public enum Foo {
    // ID is the identifier
    ID("id", "string", false),
    private final String wireKey;
    ROLE("role", "string", true);
}`;
    expect(parsePayloadFieldSource(source)).toEqual([
      { name: 'ID', wireKey: 'id', tsType: 'string', optional: false },
      { name: 'ROLE', wireKey: 'role', tsType: 'string', optional: true },
    ]);
  });
});

/**
 * C5 int 字面量解析:parseIntConstants 匹配 `public static final int NAME = literal;`,
 * literal 可含 Java 数字分隔下划线(200_000→200000),allowlist 过滤只暴露白名单常量
 * (防泄露后端其他 int 常量实现细节)。
 */
describe('parseIntConstants — C5 int literal parsing', () => {
  it('解析 int 常量并去下划线分隔(200_000→200000)', () => {
    const src = `
      public final class CommonConstants {
          public static final int DEFAULT_CONTEXT_WINDOW = 200_000;
          public static final int ONE_MILLION_CONTEXT_WINDOW = 1_000_000;
      }
    `;
    const allowlist = ['DEFAULT_CONTEXT_WINDOW', 'ONE_MILLION_CONTEXT_WINDOW'];
    expect(parseIntConstants(src, allowlist)).toEqual([
      { name: 'DEFAULT_CONTEXT_WINDOW', value: 200000 },
      { name: 'ONE_MILLION_CONTEXT_WINDOW', value: 1000000 },
    ]);
  });

  it('allowlist 过滤:仅返回白名单常量,忽略其他 int 常量', () => {
    const src = `
      public static final int KEEP_ME = 42;
      public static final int HIDDEN_INTERNAL = 999;
    `;
    expect(parseIntConstants(src, ['KEEP_ME'])).toEqual([{ name: 'KEEP_ME', value: 42 }]);
  });

  it('空源码/无匹配返回空数组', () => {
    expect(parseIntConstants('no constants here', ['X'])).toEqual([]);
    expect(parseIntConstants('', [])).toEqual([]);
  });

  it('不匹配非 int 常量(String/long 等)', () => {
    const src = `
      public static final String LABEL = "x";
      public static final long BIG = 1_000L;
    `;
    expect(parseIntConstants(src, ['LABEL', 'BIG'])).toEqual([]);
  });
});

/**
 * C1 payload 接口生成:generateFromManifest 把 manifest.payloadSchemas 转成
 * TS interface(<PascalCase(key)>PayloadWire),字段名=wireKey,optional 带 ?。
 */
describe('generateFromManifest — C1 payload interface generation', () => {
  const baseManifest = {
    upstream: [],
    downstream: [],
    permissionMode: [],
    reasoningEffort: [],
    providerType: [],
    codexProtectedEnvKey: [],
    intConstants: [],
  };

  it('生成 ModelRegistryPayloadWire interface:字段名=wireKey,optional 带 ?', () => {
    const ts = generateFromManifest({
      ...baseManifest,
      payloadSchemas: {
        modelRegistry: {
          fields: [
            { name: 'ID', wireKey: 'id', tsType: 'string', optional: false },
            { name: 'ROLE', wireKey: 'role', tsType: 'string', optional: true },
            { name: 'TAGS', wireKey: 'tags', tsType: 'readonly string[]', optional: true },
          ],
        },
      },
    });
    expect(ts).toContain('export interface ModelRegistryPayloadWire {');
    expect(ts).toContain('  id: string;');
    expect(ts).toContain('  role?: string;');
    expect(ts).toContain('  tags?: readonly string[];');
  });

  it('无 payloadSchemas 时不生成 payload interface(向后兼容)', () => {
    const ts = generateFromManifest(baseManifest);
    expect(ts).not.toContain('PayloadWire');
  });

  it('生成 CODEX_PROTECTED_ENV_KEY 常量与派生类型(A5)', () => {
    const ts = generateFromManifest({
      ...baseManifest,
      codexProtectedEnvKey: [
        { name: 'CODEX_MODEL', value: 'CODEX_MODEL' },
        { name: 'HOME', value: 'HOME' },
      ],
    });
    expect(ts).toContain('export const CODEX_PROTECTED_ENV_KEY = {');
    expect(ts).toContain("  CODEX_MODEL: 'CODEX_MODEL' as const,");
    expect(ts).toContain("  HOME: 'HOME' as const,");
    expect(ts).toContain(
      'export type CodexProtectedEnvKey = typeof CODEX_PROTECTED_ENV_KEY[keyof typeof CODEX_PROTECTED_ENV_KEY];'
    );
  });

  it('生成 int 常量为 export const X = N as const(C5)', () => {
    const ts = generateFromManifest({
      ...baseManifest,
      intConstants: [
        { name: 'DEFAULT_CONTEXT_WINDOW', value: 200000 },
        { name: 'DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS', value: 300 },
      ],
    });
    expect(ts).toContain('export const DEFAULT_CONTEXT_WINDOW = 200000 as const;');
    expect(ts).toContain('export const DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS = 300 as const;');
  });
});
