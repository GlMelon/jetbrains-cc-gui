import { beforeEach, describe, expect, it } from 'vitest';
import {
  __setCliEnvironmentForTests,
  getCliEnvironmentSnapshot,
  isProviderCliNotInstalled,
  parseCliEnvironmentPayload,
} from '../../src/utils/cliEnvironmentStatus';

describe('cliEnvironmentStatus', () => {
  beforeEach(() => {
    __setCliEnvironmentForTests(null);
  });

  describe('isProviderCliNotInstalled(门控判定语义)', () => {
    it('未就绪(检测结果未到达)时宽松放行', () => {
      expect(getCliEnvironmentSnapshot().ready).toBe(false);
      expect(isProviderCliNotInstalled('grok')).toBe(false);
      expect(isProviderCliNotInstalled('kimi')).toBe(false);
    });

    it('已确认未安装的 provider 判定 true', () => {
      __setCliEnvironmentForTests({
        claude: { name: 'claude', displayName: 'Claude CLI', installed: true },
        grok: { name: 'grok', displayName: 'Grok CLI', installed: false },
      });
      expect(isProviderCliNotInstalled('grok')).toBe(true);
      expect(isProviderCliNotInstalled('claude')).toBe(false);
    });

    it('不在检测范围内的 provider(omp/dsh)永远放行', () => {
      __setCliEnvironmentForTests({
        claude: { name: 'claude', displayName: 'Claude CLI', installed: true },
      });
      expect(isProviderCliNotInstalled('omp')).toBe(false);
      expect(isProviderCliNotInstalled('dsh')).toBe(false);
    });
  });

  describe('parseCliEnvironmentPayload(事件负载解析)', () => {
    it('解析以 CLI 名为 key 的扁平 map', () => {
      const parsed = parseCliEnvironmentPayload(JSON.stringify({
        claude: { name: 'claude', installed: true },
        grok: { name: 'grok', installed: false },
      }));
      expect(parsed).not.toBeNull();
      expect(parsed?.grok.installed).toBe(false);
    });

    it('后端 error 分支({error,timestamp} 无 per-tool 键)返回 null → 保持未就绪', () => {
      expect(parseCliEnvironmentPayload(JSON.stringify({ error: 'boom', timestamp: 1 }))).toBeNull();
    });

    it('非法 JSON 返回 null', () => {
      expect(parseCliEnvironmentPayload('not-json{')).toBeNull();
    });
  });
});
