import { memo } from 'react';
import type { TFunction } from 'i18next';
import { SettingsIcon, ArrowRightIcon } from '../Icons';

interface ProviderNotConfiguredCardProps {
  t: TFunction;
  onNavigateToSettings?: () => void;
}


/**
 * Detects whether an error message indicates a provider-not-configured error.
 * The backend throws "API Key not configured and no CLI session found" and may
 * append Node.js diagnostics, so we match on the leading substring.
 */
export function isProviderNotConfiguredError(errorText: string): boolean {
  return errorText.includes('API Key not configured')
    || errorText.includes('local configuration access is not authorized')
    || errorText.includes('本地配置读取未获授权');
}

export const ProviderNotConfiguredCard = memo(function ProviderNotConfiguredCard({
  t,
  onNavigateToSettings,
}: ProviderNotConfiguredCardProps) {
  return (
    <div className="provider-not-configured-card">
      <div className="provider-card-header">
        <span className="provider-card-icon">
          <SettingsIcon />
        </span>
        <span className="provider-card-title">
          {t('error.providerNotConfigured')}
        </span>
      </div>
      <p className="provider-card-description">
        {t('error.providerNotConfiguredDesc')}
      </p>
      {onNavigateToSettings && (
        <button
          type="button"
          className="provider-card-action"
          onClick={onNavigateToSettings}
        >
          {t('error.goToProviderSettings')}
          <ArrowRightIcon />
        </button>
      )}
    </div>
  );
});
