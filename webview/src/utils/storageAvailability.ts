/**
 * 检测 localStorage 是否真正可用(可写入)。
 *
 * D2(去重):原 `inputHistoryStorage.ts`(仅存在性检测)与 `useAttachmentPersistence.ts`
 * (写入测试)各持一份同名 `canUseLocalStorage`,语义不一致。统一为"写入测试"实现 ——
 * 仅存在性检测无法识别 Safari 隐私模式(localStorage 存在但 setItem 抛 QuotaExceededError),
 * 写入测试才是业界标准的可用性判定。
 *
 * @returns localStorage 可写入时 true;不可用、抛错或非浏览器环境时 false
 */
export function canUseLocalStorage(): boolean {
  try {
    const testKey = '__localStorage_test__';
    window.localStorage.setItem(testKey, 'test');
    window.localStorage.removeItem(testKey);
    return true;
  } catch {
    return false;
  }
}
