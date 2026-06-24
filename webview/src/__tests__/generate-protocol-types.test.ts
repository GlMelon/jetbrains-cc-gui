import { describe, it, expect, vi } from 'vitest';
import { parseEnumSource, parsePayloadFieldSource, generateFromManifest } from '../../scripts/generate-protocol-types.mjs';

/**
 * C8 漂移守门测试:parseEnumSource 严格 regex 仅匹配 NAME("value") 单参,
 * 多参格式(如未来 C2 加 desc:NAME("value","desc"))会被漏。宽松启发计数与严格
 * regex 计数不一致时必须 WARN,防止静默漏项。
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

  it('WARNS when multi-arg entries drift past the strict regex', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    // 模拟 C2 未来加 desc 的多参形态:NAME("value","desc") 严格 regex 漏,宽松启发捕获
    const src = `
      public enum X implements ProtocolValue {
        FOO("foo"),
        BAR("bar", "bar-desc"),
      }
    `;
    const entries = parseEnumSource(src, 'X.java');
    // 严格 regex 仅匹配 FOO(单参);BAR 多参被漏
    expect(entries).toEqual([{ name: 'FOO', value: 'foo' }]);
    // 漂移告警必须触发(验收:格式变化时有显式告警)
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
});
