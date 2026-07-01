import { useTranslation } from 'react-i18next';
import { CLAUDE_MODEL_MAPPING_ENV_KEYS } from '../../../types/provider';
import type { ClaudeConfigFormState } from './adapters';

/**
 * Claude env 保留 key(已在凭证步/模型映射步有独立表单字段)。
 * 表单视图不显示这些 key,避免双写冲突;它们仍存在于 env(JSON 视图可见可编辑,作真相源)。
 */
const RESERVED_ENV_KEYS: readonly string[] = [
  ...CLAUDE_MODEL_MAPPING_ENV_KEYS,
  'ANTHROPIC_AUTH_TOKEN',
  'ANTHROPIC_API_KEY',
  'ANTHROPIC_BASE_URL',
  'ANTHROPIC_SMALL_FAST_MODEL',
];

/** env 中非保留 key 的自定义条目(保留 key 已在凭证/模型步有独立字段)。 */
function filterCustomEnv(env: unknown): Array<[string, any]> {
  if (!env || typeof env !== 'object' || Array.isArray(env)) return [];
  return Object.entries(env as Record<string, any>).filter(
    ([k]) => !RESERVED_ENV_KEYS.includes(k),
  );
}

interface EnvRecordEditorProps {
  /** 整个 settingsConfig(env 在 config.env)。 */
  config: ClaudeConfigFormState;
  onChange: (next: ClaudeConfigFormState) => void;
}

/**
 * Claude 自定义环境变量表单视图 —— 编辑 settingsConfig.env 中**非保留 key** 的条目。
 *
 * <p>数据是对象 Record<string,any>(区别于 Codex 的 EnvVarEntry[] 数组)。保留 key
 * (ANTHROPIC_AUTH_TOKEN/BASE_URL/MODEL/DEFAULT_*)已在凭证步/模型步有独立字段,
 * 此处不显示避免双写;它们仍保留在 env 中,JSON 视图可见。
 * onChange 只更新 config.env,保留 config 非 env 顶层字段(model/alwaysThinkingEnabled 等)。
 */
export default function EnvRecordEditor({ config, onChange }: EnvRecordEditorProps) {
  const { t } = useTranslation();
  const env: Record<string, any> =
    config.env && typeof config.env === 'object' && !Array.isArray(config.env)
      ? (config.env as Record<string, any>)
      : {};
  const customEntries = filterCustomEnv(env);

  const updateEnv = (nextEnv: Record<string, any>) => {
    onChange({ ...config, env: nextEnv });
  };

  const handleValueChange = (key: string, value: string) => {
    updateEnv({ ...env, [key]: value });
  };

  const handleKeyChange = (oldKey: string, newKey: string) => {
    if (newKey === oldKey) return;
    const { [oldKey]: _omitted, ...rest } = env;
    updateEnv({ ...rest, [newKey]: env[oldKey] });
  };

  const handleDelete = (key: string) => {
    const { [key]: _omitted, ...rest } = env;
    updateEnv(rest);
  };

  const handleAdd = () => {
    let base = 'NEW_KEY';
    let n = 1;
    while (Object.prototype.hasOwnProperty.call(env, base)) {
      base = `NEW_KEY_${++n}`;
    }
    updateEnv({ ...env, [base]: '' });
  };

  return (
    <div className="env-record-editor">
      {customEntries.map(([k, v]) => (
        <div className="env-record-row" key={k}>
          <input
            className="env-record-key form-input"
            aria-label={`env-key-${k}`}
            value={k}
            onChange={(e) => handleKeyChange(k, e.target.value)}
          />
          <input
            className="env-record-value form-input"
            aria-label={`env-value-${k}`}
            value={String(v ?? '')}
            onChange={(e) => handleValueChange(k, e.target.value)}
          />
          <button
            type="button"
            className="env-record-delete"
            aria-label={`env-delete-${k}`}
            onClick={() => handleDelete(k)}
          >
            ×
          </button>
        </div>
      ))}
      <button type="button" className="env-record-add" onClick={handleAdd}>
        + {t('settings.provider.dialog.addCustomEnv', '新增自定义环境变量')}
      </button>
    </div>
  );
}
