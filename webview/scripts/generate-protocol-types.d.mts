// generate-protocol-types.mjs 的类型声明(供 webview 测试 import 类型检查)
// .d.mts 与 .mjs 配对(TS ESM 约定)。维护:函数签名/类型变化时同步本声明。

export interface ProtocolEnumEntry {
  name: string;
  value: string;
}

export interface IntConstantEntry {
  name: string;
  value: number;
}

export declare function parseEnumSource(source: string, label?: string): ProtocolEnumEntry[];

// ── C1:payload wire 字段声明解析 + manifest 生成 ──

export interface PayloadField {
  name: string;
  wireKey: string;
  tsType: string;
  optional: boolean;
}

export interface PayloadSchema {
  fields: PayloadField[];
}

export interface ProtocolManifest {
  upstream: ProtocolEnumEntry[];
  downstream: ProtocolEnumEntry[];
  permissionMode?: ProtocolEnumEntry[];
  reasoningEffort?: ProtocolEnumEntry[];
  providerType?: ProtocolEnumEntry[];
  codexProtectedEnvKey?: ProtocolEnumEntry[];
  versionAction?: ProtocolEnumEntry[];
  intConstants?: IntConstantEntry[];
  payloadSchemas?: Record<string, PayloadSchema>;
}

/** 解析三参 Java payload 字段枚举 NAME("wireKey","tsType",optional)(C1)。 */
export declare function parsePayloadFieldSource(source: string, label?: string): PayloadField[];

/** 解析 Java int 常量 public static final int NAME = literal(C5),allowlist 过滤暴露。 */
export declare function parseIntConstants(source: string, allowlist?: string[], label?: string): IntConstantEntry[];

/** 从 manifest 生成完整 protocol.ts 文本(含 UPSTREAM/DOWNSTREAM/payload 接口)(C1)。 */
export declare function generateFromManifest(manifest: ProtocolManifest): string;
