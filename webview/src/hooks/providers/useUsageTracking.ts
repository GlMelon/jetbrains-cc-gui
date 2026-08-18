import { useState } from 'react';
import type { TokenDetail } from '../../components/ChatInputBox/types';

/**
 * Usage % / token counters.
 *
 * (SDK install-status checks removed — CLI mode uses executables, no npm SDK
 * packages; provider availability is config-driven, see CodemossSettings.)
 */
export function useUsageTracking() {
  const [usagePercentage, setUsagePercentage] = useState(0);
  const [usageUsedTokens, setUsageUsedTokens] = useState<number | undefined>(undefined);
  const [usageMaxTokens, setUsageMaxTokens] = useState<number | undefined>(undefined);
  const [tokenDetail, setTokenDetail] = useState<TokenDetail | undefined>(undefined);

  return {
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
    tokenDetail,
    setTokenDetail,
  };
}
