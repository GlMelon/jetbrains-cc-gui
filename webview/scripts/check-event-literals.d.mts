export interface ProtocolEntry {
  name: string;
  value: string;
}

export declare function parseGeneratedProtocolConstant(
  protocolSource: string,
  constantName: string,
): ProtocolEntry[];

export declare function compareProtocolEntries(
  reference: ProtocolEntry[],
  actual: ProtocolEntry[],
  referenceLabel: string,
  actualLabel: string,
): string[];
