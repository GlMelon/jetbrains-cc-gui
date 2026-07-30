import { useTranslation } from 'react-i18next';
import type { OpenCodeAdvancedFormState } from './dualView/adapters';

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
