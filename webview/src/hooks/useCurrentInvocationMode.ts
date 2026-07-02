import { useEffect, useState } from 'react';
import { sendAction, subscribeEvent } from '../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../generated/protocol';

export type InvocationMode = 'sdk' | 'cli';

/**
 * 订阅当前项目调用模式(sdk / cli)。
 *
 * 后端 `ClaudeSDKToolWindow.broadcastInvocationMode` 已把 CONFIG_INVOCATION_MODE
 * 广播到所有 chat window,本 hook 在主聊天界面补上订阅(此前仅 settings 窗口订阅),
 * 并在 mount 时主动通过 GET_INVOCATION_MODE 拉取当前值。用于 UI 按「当前调用模式」
 * 精确控制可见性——例如 OpenCode CLI 模式下 `opencode run --format json` 的 NDJSON
 * 无推理文本事件,思考开关是 no-op,故应灰显;而 SDK 模式有 reasoning 事件,开关正常生效。
 *
 * @returns 当前后端已知调用模式;mount 后尚未收到下行时为 undefined
 */
export function useCurrentInvocationMode(): InvocationMode | undefined {
  const [mode, setMode] = useState<InvocationMode | undefined>(undefined);
  useEffect(() => {
    const unsub = subscribeEvent(DOWNSTREAM.CONFIG_INVOCATION_MODE, (payload) => {
      try {
        const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
        const data = JSON.parse(raw) as { invocationMode?: unknown };
        if (data.invocationMode === 'sdk' || data.invocationMode === 'cli') {
          setMode(data.invocationMode);
        }
      } catch {
        // 解析失败时保留上次已知值(可能为 undefined)
      }
    });
    sendAction(UPSTREAM.GET_INVOCATION_MODE);
    return unsub;
  }, []);
  return mode;
}
