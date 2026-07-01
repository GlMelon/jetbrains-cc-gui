import { useEffect, useState, type ReactNode } from 'react';
import type { DualViewAdapter } from './dualView/adapters';
import JsonEditor from './JsonEditor';

export type DualViewMode = 'form' | 'json';

export interface DualViewSwitcherProps<S> {
  /** 区块标题(可选)。 */
  label?: string;
  /** 表单状态(SSOT,父级持有)。 */
  formState: S;
  /** 表单状态变更回调(边界同步时调用)。 */
  onFormStateChange: (next: S) => void;
  /** 双向同步适配器。 */
  adapter: DualViewAdapter<S>;
  /** 表单视图渲染(调用方提供具体字段)。 */
  renderForm: (state: S, onChange: (next: S) => void) => ReactNode;
  /** 当前视图模式(受控)。 */
  mode: DualViewMode;
  onModeChange: (mode: DualViewMode) => void;
  /** JSON 视图附加提示(如 OpenCode「含透传字段」)。 */
  jsonHint?: string;
}

/**
 * 分块 JSON↔表单 双视图切换器(仅管环境变量/参数区块,不管 models/baseURL/apiKey)。
 *
 * 状态机核心(「切换不丢数据」):
 * - `jsonDraft`:JSON 草稿文本,JSON 模式下编辑只更新它,不动 formState。
 * - `parseError`:实时解析反馈(输入即解析)。
 * - **边界同步**(textarea blur 或切回 form 模式):apply pending;parse/validate 失败 →
 *   阻止切换 + 红提示 + 留在 JSON 模式(草稿保留,绝不丢数据)。
 *
 * mode 受控(父级持有);切到 JSON 时用最新 formState 重置草稿(JSON 视图反映最新表单)。
 */
export default function DualViewSwitcher<S>({
  label,
  formState,
  onFormStateChange,
  adapter,
  renderForm,
  mode,
  onModeChange,
  jsonHint,
}: DualViewSwitcherProps<S>) {
  const [jsonDraft, setJsonDraft] = useState(() => adapter.serialize(formState));
  const [parseError, setParseError] = useState<string | null>(null);

  // 切到 JSON 模式时,用最新 formState 重置草稿(JSON 视图反映最新表单)。
  // 刻意不依赖 formState:JSON 模式下草稿独立,不跟随外部 formState 变化。
  useEffect(() => {
    if (mode === 'json') {
      setJsonDraft(adapter.serialize(formState));
      setParseError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  const handleJsonChange = (next: string) => {
    setJsonDraft(next);
    const result = adapter.parse(next);
    setParseError(result.ok ? null : result.error);
  };

  // 边界同步:解析 + 校验 + apply。成功返回 true,失败返回 false(调用方决定是否切模式)。
  const applyPending = (): boolean => {
    const result = adapter.parse(jsonDraft);
    if (!result.ok) {
      setParseError(result.error);
      return false;
    }
    const validationError = adapter.validate ? adapter.validate(result.state) : null;
    if (validationError) {
      setParseError(validationError);
      return false;
    }
    onFormStateChange(result.state);
    setParseError(null);
    return true;
  };

  const handleJsonBlur = () => {
    applyPending(); // 不切模式,仅把合法草稿同步回 formState
  };

  const handleSwitchToForm = () => {
    if (applyPending()) {
      onModeChange('form');
    }
    // 失败则阻止切换:留在 JSON 模式,parseError 已显示,草稿保留(不丢数据)。
  };

  const handleSwitchToJson = () => {
    setJsonDraft(adapter.serialize(formState));
    setParseError(null);
    onModeChange('json');
  };

  return (
    <div className="dvs">
      <div className="dvs-header">
        {label && <span className="dvs-label">{label}</span>}
        <div className="dvs-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'form'}
            className={`dvs-tab${mode === 'form' ? ' active' : ''}`}
            disabled={mode === 'form'}
            onClick={handleSwitchToForm}
          >
            表单
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'json'}
            className={`dvs-tab${mode === 'json' ? ' active' : ''}`}
            disabled={mode === 'json'}
            onClick={handleSwitchToJson}
          >
            JSON
          </button>
        </div>
        {mode === 'json' && jsonHint && <span className="dvs-hint">{jsonHint}</span>}
      </div>
      <div className="dvs-body">
        {mode === 'form'
          ? renderForm(formState, onFormStateChange)
          : (
            <JsonEditor
              value={jsonDraft}
              onChange={handleJsonChange}
              onBlur={handleJsonBlur}
              error={parseError}
            />
          )}
      </div>
    </div>
  );
}
