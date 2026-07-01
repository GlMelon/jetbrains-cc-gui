import { useTranslation } from 'react-i18next';
import type { OpenCodeProviderConfig } from '../../types/provider';
import type { OpenCodeAdvancedFormState } from './dualView/adapters';

/**
 * opencode.json provider 段的已知业务字段(已被 dialog 基本步/凭证步/模型步覆盖),
 * 不进入「高级/透传」raw 段。其余原生字段(npm/options/command 等)作透传。
 */
const ADVANCED_RAW_EXCLUDE = [
  'id',
  'name',
  'baseURL',
  'apiBase',
  'apiKey',
  'models',
  'createdAt',
  'isActive',
] as const;

/**
 * 从 OpenCodeProviderConfig 提取「高级/透传」raw 段:剥离已知业务字段,保留 opencode 原生字段。
 *
 * <p>用途:编辑模式回灌时,provider 携带的原生字段(options/npm/command 等)不能被 dialog 丢失,
 * 故提取到 raw 段经 DualViewSwitcher(JSON 视图完整可编辑,表单视图仅覆盖高频字段 npm),
 * save 时 {@code ...raw} 原样透传落盘(见 OpenCodeProviderDialog.handleSave)。
 */
export function extractAdvancedRaw(
  provider: Partial<OpenCodeProviderConfig>,
): Record<string, any> {
  const raw: Record<string, any> = {};
  for (const [k, v] of Object.entries(provider || {})) {
    if (!(ADVANCED_RAW_EXCLUDE as readonly string[]).includes(k)) {
      raw[k] = v;
    }
  }
  return raw;
}

interface OpenCodeAdvancedFormProps {
  state: OpenCodeAdvancedFormState;
  onChange: (next: OpenCodeAdvancedFormState) => void;
}

/**
 * OpenCode 高级字段表单视图 —— 仅覆盖高频易表单字段(npm 包名,字符串)。
 *
 * <p>opencode 原生字段多为嵌套对象/命令(options/command 等),表单化收益低且结构不稳定,
 * 故表单视图只列 npm + 提示;其余透传字段只在 DualViewSwitcher 的 JSON 视图可见可编辑。
 */
export default function OpenCodeAdvancedForm({
  state,
  onChange,
}: OpenCodeAdvancedFormProps) {
  const { t } = useTranslation();
  const npm = (state.raw.npm as string | undefined) ?? '';

  return (
    <div className="form-group" style={{ marginTop: '16px' }}>
      <label htmlFor="opencodeNpmPackage">
        {t('settings.openCodeProvider.dialog.npmPackage', 'npm 包名(可选)')}
      </label>
      <input
        id="opencodeNpmPackage"
        aria-label="npm-package"
        type="text"
        className="form-input"
        placeholder="@opencode/opencode"
        value={npm}
        onChange={(e) => onChange({ raw: { ...state.raw, npm: e.target.value } })}
      />
      <small className="form-hint">
        {t(
          'settings.openCodeProvider.dialog.advancedFormHint',
          '其余高级字段(如 options / command)请切换 JSON 视图编辑',
        )}
      </small>
    </div>
  );
}
