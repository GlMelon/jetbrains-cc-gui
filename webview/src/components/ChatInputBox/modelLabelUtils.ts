import type { ModelInfo } from './types';

type Translate = (key: string, options?: { defaultValue?: string } & Record<string, unknown>) => string;

export const MODEL_LABEL_KEYS: Record<string, string> = {
  'claude-opus-5': 'models.claude.opus5.label',
  'claude-sonnet-5': 'models.claude.sonnet5.label',
  'claude-sonnet-4-6': 'models.claude.sonnet46.label',
  'claude-fable-5': 'models.claude.fable5.label',
  'claude-opus-4-8': 'models.claude.opus48.label',
  'claude-opus-4-6': 'models.claude.opus46_1m.label',
  'claude-opus-4-6[1m]': 'models.claude.opus46_1m.label',
  'claude-haiku-4-5': 'models.claude.haiku45.label',
  'gpt-5.6-sol': 'models.codex.gpt56sol.label',
  'gpt-5.6-terra': 'models.codex.gpt56terra.label',
  'gpt-5.6-luna': 'models.codex.gpt56luna.label',
  'gpt-5.5': 'models.codex.gpt55.label',
  'gpt-5.4': 'models.codex.gpt54.label',
  'grok-4.6': 'models.grok.grok46.label',
  'grok-4.5': 'models.grok.grok46.label',
  grok: 'models.grok.grok46.label',
};

export const MODEL_DESCRIPTION_KEYS: Record<string, string> = {
  'claude-opus-5': 'models.claude.opus5.description',
  'claude-sonnet-5': 'models.claude.sonnet5.description',
  'claude-sonnet-4-6': 'models.claude.sonnet46.description',
  'claude-fable-5': 'models.claude.fable5.description',
  'claude-opus-4-8': 'models.claude.opus48.description',
  'claude-opus-4-6': 'models.claude.opus46_1m.description',
  'claude-opus-4-6[1m]': 'models.claude.opus46_1m.description',
  'claude-haiku-4-5': 'models.claude.haiku45.description',
  'gpt-5.6-sol': 'models.codex.gpt56sol.description',
  'gpt-5.6-terra': 'models.codex.gpt56terra.description',
  'gpt-5.6-luna': 'models.codex.gpt56luna.description',
  'gpt-5.5': 'models.codex.gpt55.description',
  'gpt-5.4': 'models.codex.gpt54.description',
  'grok-4.6': 'models.grok.grok46.description',
  'grok-4.5': 'models.grok.grok46.description',
  grok: 'models.grok.grok46.description',
};

/**
 * Maps model IDs to mapping keys for looking up actual model names
 * from the 'claude-model-mapping' localStorage entry.
 * Legacy Opus 4.6 IDs share the same opus mapping bucket.
 */
export const MODEL_ID_TO_MAPPING_KEY: Record<string, string> = {
  'claude-fable-5': 'fable',
  'claude-opus-5': 'opus',
  'claude-sonnet-5': 'sonnet',
  'claude-sonnet-4-7': 'sonnet',
  'claude-sonnet-4-6': 'sonnet',
  'claude-opus-4-8': 'opus',
  'claude-opus-4-6': 'opus',
  'claude-opus-4-6[1m]': 'opus',
  'claude-haiku-4-5': 'haiku',
};

export const resolveMappedModelName = (
  mappingKey: string | undefined,
  modelMapping: Record<string, string | undefined>,
): string | undefined => {
  if (!mappingKey) {
    return modelMapping.main?.trim() || undefined;
  }

  const mapped = modelMapping[mappingKey]
    || (mappingKey === 'opus_1m' ? modelMapping.opus : undefined)
    || modelMapping.main;

  return mapped?.trim() || undefined;
};

/**
 * Resolve the display model name for icon matching.
 * For mapped Claude models, returns the mapped name; otherwise the original ID.
 */
export const resolveModelIdForIcon = (
  modelId: string,
  modelMapping: Record<string, string | undefined>,
  mappingKeyMap: Record<string, string> = MODEL_ID_TO_MAPPING_KEY,
): string => {
  const mappingKey = mappingKeyMap[modelId];
  if (!mappingKey) {
    return modelId;
  }
  const mapped = resolveMappedModelName(mappingKey, modelMapping);
  if (mapped) {
    return mapped;
  }
  return modelId;
};

const append1MContextSuffix = (
  label: string,
  currentProvider: string,
  show1MContext: boolean,
  longContextEnabled: boolean,
  t: Translate,
  supports1M?: boolean,
): string => {
  // A4(2026-06-23):静态 modelSupports1MContext 已删除,1M 能力读 registry 下发的 supports1MContext 字段。
  if (currentProvider === 'claude' && show1MContext && supports1M && longContextEnabled) {
    return `${label} (${t('models.longContext.shortLabel')})`;
  }
  return label;
};

export function resolveModelDisplayLabel(
  model: ModelInfo,
  options: {
    t: Translate;
    currentProvider?: string;
    modelMapping?: Record<string, string | undefined>;
    show1MContext?: boolean;
    longContextEnabled?: boolean;
  },
): string {
  const {
    t,
    currentProvider = 'claude',
    modelMapping = {},
    show1MContext = false,
    longContextEnabled = true,
  } = options;

  if (currentProvider !== 'claude') {
    return append1MContextSuffix(
      model.label ?? '',
      currentProvider,
      show1MContext,
      longContextEnabled,
      t,
      model.supports1MContext,
    );
  }

  const mappingKey = MODEL_ID_TO_MAPPING_KEY[model.id];
  if (mappingKey) {
    const mappedName = resolveMappedModelName(mappingKey, modelMapping);
    if (mappedName) {
      return append1MContextSuffix(
        mappedName,
        currentProvider,
        show1MContext,
        longContextEnabled,
        t,
      model.supports1MContext,
      );
    }
  }

  const labelKey = MODEL_LABEL_KEYS[model.id];
  // A3:内置模型判定读 registry 的 role 字段;无 role 视为自定义模型(用自身 label)。
  const hasCustomLabel = !model.role && !!model.label;

  if (hasCustomLabel) {
    return append1MContextSuffix(
      model.label ?? '',
      currentProvider,
      show1MContext,
      longContextEnabled,
      t,
      model.supports1MContext,
    );
  }

  if (labelKey) {
    return append1MContextSuffix(
      t(labelKey),
      currentProvider,
      show1MContext,
      longContextEnabled,
      t,
      model.supports1MContext,
    );
  }

  return append1MContextSuffix(
    model.label ?? '',
    currentProvider,
    show1MContext,
    longContextEnabled,
    t,
      model.supports1MContext,
  );
}

export function resolveModelDescription(model: ModelInfo, t: Translate): string | undefined {
  const descriptionKey = MODEL_DESCRIPTION_KEYS[model.id];
  if (descriptionKey) {
    return t(descriptionKey);
  }
  return model.description;
}
