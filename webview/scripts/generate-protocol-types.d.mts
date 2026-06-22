// generate-protocol-types.mjs 的类型声明(供 webview 测试 import 类型检查)
// .d.mts 与 .mjs 配对(TS ESM 约定)。维护:parseEnumSource 签名变化时同步本声明。

export interface ProtocolEnumEntry {
  name: string;
  value: string;
}

export declare function parseEnumSource(source: string, label?: string): ProtocolEnumEntry[];
