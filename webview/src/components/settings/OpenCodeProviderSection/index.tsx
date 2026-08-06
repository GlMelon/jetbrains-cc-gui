import { sendAction } from '../../../bridge/typed';
import { BanIcon, CheckIcon, EditIcon, GripIcon, InfoIcon, KeyIcon, PlusIcon, PowerIcon, TrashIcon } from '../../Icons';
import { UPSTREAM } from '../../../generated/protocol';
import { useState, useCallback, useMemo } from 'react';

import { useTranslation } from 'react-i18next';

import type { OpenCodeProviderConfig } from '../../../types/provider';

import { SPECIAL_PROVIDER_IDS } from '../../../types/provider';

import { useDragSort } from '../hooks/useDragSort';

import sharedStyles from '../ProviderList/style.module.less';
import styles from './style.module.less';
import { UnifiedLoader } from '../../UnifiedLoader';

const ICON_MR_8_STYLE: React.CSSProperties = { marginRight: '8px' };

interface OpenCodeProviderSectionProps {
  openCodeProviders: OpenCodeProviderConfig[];
  openCodeLoading: boolean;
  onAddOpenCodeProvider: () => void;
  onEditOpenCodeProvider: (provider: OpenCodeProviderConfig) => void;
  onDeleteOpenCodeProvider: (provider: OpenCodeProviderConfig) => void;
  onSwitchOpenCodeProvider: (id: string) => void;
  onRevokeOpenCodeLocalConfigAuthorization: (fallbackProviderId?: string) => void;
  showHeader?: boolean;
}

/**
 * OpenCode provider 管理列表 —— 对称 {@link CodexProviderSection}(Principle 6)。
 * 含本地配置虚拟 provider 卡片(「从配置文件授权」诉求③)+ 管理型 provider 拖拽排序 + CRUD。
 */
const OpenCodeProviderSection = ({
  openCodeProviders,
  openCodeLoading,
  onAddOpenCodeProvider,
  onEditOpenCodeProvider,
  onDeleteOpenCodeProvider,
  onSwitchOpenCodeProvider,
  onRevokeOpenCodeLocalConfigAuthorization,
  showHeader = true,
}: OpenCodeProviderSectionProps) => {
  const { t } = useTranslation();

  const [showLocalConfigConfirm, setShowLocalConfigConfirm] = useState(false);
  const [showLocalConfigDisableConfirm, setShowLocalConfigDisableConfirm] = useState(false);

  const onSort = useCallback((orderedIds: string[]) => {
    sendAction(UPSTREAM.SORT_OPENCODE_PROVIDERS, { orderedIds });
  }, []);

  // Filter out local-config virtual provider from drag-sort list
  const regularProviders = useMemo(
    () => openCodeProviders.filter((p) => p.id !== SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG),
    [openCodeProviders]
  );

  const {
    localItems: localProviders,
    draggedId: draggedProviderId,
    dragOverId: dragOverProviderId,
    handlePointerDown,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    handleDragEnd,
  } = useDragSort({
    items: regularProviders,
    onSort,
  });

  const localConfigProvider = useMemo(
    () => openCodeProviders.find((p) => p.id === SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG),
    [openCodeProviders]
  );
  const isLocalConfigActive = localConfigProvider?.isActive === true;

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.openCodeProvider.title')}</h3>
          <p className={styles.sectionDesc}>{t('settings.openCodeProvider.description')}</p>
        </>
      )}

      {/* Local config authorize confirm dialog */}
      {showLocalConfigConfirm && (
        <div className={sharedStyles.warningOverlay}>
          <div className={sharedStyles.warningDialog}>
            <div className={sharedStyles.warningTitle}>
              <KeyIcon size={16} />
              {t('settings.openCodeProvider.dialog.localConfigAuthorizeTitle')}
            </div>
            <div className={sharedStyles.warningContent}>
              {t('settings.openCodeProvider.dialog.localConfigAuthorizeMessage')}
              <br />
              <br />
              {t('settings.openCodeProvider.dialog.localConfigAuthorizeDetail')}
            </div>
            <div className={sharedStyles.warningActions}>
              <button
                className={sharedStyles.btnSecondary}
                onClick={() => setShowLocalConfigConfirm(false)}
              >
                {t('common.cancel')}
              </button>
              <button
                className={sharedStyles.btnPrimary}
                onClick={() => {
                  setShowLocalConfigConfirm(false);
                  onSwitchOpenCodeProvider(SPECIAL_PROVIDER_IDS.OPENCODE_LOCAL_CONFIG);
                }}
              >
                {t('settings.provider.authorizeAndEnable')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Local config disable confirm dialog */}
      {showLocalConfigDisableConfirm && (
        <div className={sharedStyles.warningOverlay}>
          <div className={sharedStyles.warningDialog}>
            <div className={sharedStyles.warningTitle}>
              <BanIcon size={16} />
              {t('settings.openCodeProvider.dialog.localConfigDisableTitle')}
            </div>
            <div className={sharedStyles.warningContent}>
              {t('settings.openCodeProvider.dialog.localConfigDisableMessage')}
            </div>
            <div className={sharedStyles.warningActions}>
              <button
                className={sharedStyles.btnSecondary}
                onClick={() => setShowLocalConfigDisableConfirm(false)}
              >
                {t('common.cancel')}
              </button>
              <button
                className={sharedStyles.btnDanger}
                onClick={() => {
                  setShowLocalConfigDisableConfirm(false);
                  const firstRegular = regularProviders[0];
                  onRevokeOpenCodeLocalConfigAuthorization(firstRegular?.id);
                }}
              >
                {t('settings.provider.revokeAuthorization')}
              </button>
            </div>
          </div>
        </div>
      )}

      {openCodeLoading && (
        <div className={styles.tempNotice}>
          <UnifiedLoader type="orbit" size={16} />
          <p>{t('settings.provider.loading')}</p>
        </div>
      )}

      {!openCodeLoading && (
        <div>
          <div className={sharedStyles.header}>
            <h4 className={sharedStyles.title}>{t('settings.provider.allProviders')}</h4>
            <div className={sharedStyles.actions}>
              <button
                className={sharedStyles.btnPrimary}
                onClick={onAddOpenCodeProvider}
              >
                <PlusIcon size={16} />
                {t('common.add')}
              </button>
            </div>
          </div>

          <div className={sharedStyles.list}>
            {/* Local config virtual provider card (pinned at top) */}
            {localConfigProvider && (
              <div
                className={[
                  sharedStyles.card,
                  isLocalConfigActive ? sharedStyles.active : '',
                  sharedStyles.localProviderCard,
                ].filter(Boolean).join(' ')}
              >
                <div className={sharedStyles.cardInfo}>
                  <div className={sharedStyles.name}>
                    <KeyIcon size={16} style={ICON_MR_8_STYLE} />
                    {t('settings.openCodeProvider.dialog.localConfigProviderName')}
                  </div>
                  <div className={sharedStyles.website} title={t('settings.openCodeProvider.dialog.localConfigProviderDescription')}>
                    {t('settings.openCodeProvider.dialog.localConfigProviderDescription')}
                  </div>
                </div>

                <div className={sharedStyles.cardActions}>
                  {isLocalConfigActive ? (
                    <button
                      className={sharedStyles.revokeButton}
                      onClick={() => setShowLocalConfigDisableConfirm(true)}
                    >
                      <BanIcon size={16} />
                      {t('settings.provider.revokeAuthorization')}
                    </button>
                  ) : (
                    <button
                      className={sharedStyles.useButton}
                      onClick={() => setShowLocalConfigConfirm(true)}
                    >
                      <PowerIcon size={16} />
                      {t('settings.provider.authorizeAndEnable')}
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* Regular providers (drag-sortable) */}
            {localProviders.length > 0 ? (
              localProviders.map((provider) => (
                <div
                  key={provider.id}
                  className={[
                    sharedStyles.card,
                    provider.isActive && sharedStyles.active,
                    draggedProviderId === provider.id && styles.dragging,
                    dragOverProviderId === provider.id && styles.dragOver,
                  ].filter(Boolean).join(' ')}
                  data-drag-sort-id={provider.id}
                  draggable={true}
                  onDragStart={(e) => handleDragStart(e, provider.id)}
                  onDragOver={(e) => handleDragOver(e, provider.id)}
                  onDragLeave={handleDragLeave}
                  onDrop={(e) => handleDrop(e, provider.id)}
                  onDragEnd={handleDragEnd}
                >
                  <div
                    className={sharedStyles.dragHandle}
                    title={t('settings.provider.dragToSort')}
                    onPointerDown={(e) => handlePointerDown(e, provider.id, e.currentTarget.closest<HTMLElement>('[data-drag-sort-id]'))}
                  >
                    <GripIcon size={16} />
                  </div>
                  <div className={sharedStyles.cardInfo}>
                    <div className={sharedStyles.name}>{provider.name}</div>
                    {(provider.baseURL || provider.apiBase) && (
                      <div className={sharedStyles.website}>{provider.baseURL || provider.apiBase}</div>
                    )}
                  </div>

                  <div className={sharedStyles.cardActions}>
                    {provider.isActive ? (
                      <div className={sharedStyles.activeBadge}>
                        <CheckIcon size={16} />
                        {t('settings.provider.inUse')}
                      </div>
                    ) : (
                      <button
                        className={sharedStyles.useButton}
                        onClick={() => onSwitchOpenCodeProvider(provider.id)}
                      >
                        <PowerIcon size={16} />
                        {t('settings.provider.enable')}
                      </button>
                    )}

                    <div className={sharedStyles.divider} />

                    <div className={sharedStyles.actionButtons}>
                      <button
                        className={sharedStyles.iconBtn}
                        onClick={() => onEditOpenCodeProvider(provider)}
                        title={t('common.edit')}
                      >
                        <EditIcon size={16} />
                      </button>
                      <button
                        className={sharedStyles.iconBtn}
                        onClick={() => onDeleteOpenCodeProvider(provider)}
                        title={t('common.delete')}
                      >
                        <TrashIcon size={16} />
                      </button>
                    </div>
                  </div>
                </div>
              ))
            ) : !localConfigProvider ? (
              <div className={sharedStyles.emptyState}>
                <InfoIcon size={16} />
                <p>{t('settings.openCodeProvider.emptyProvider')}</p>
              </div>
            ) : null}
          </div>

          <div className={styles.infoSection}>
            <InfoIcon size={16} />
            <p>{t('settings.openCodeProvider.info')}</p>
          </div>
        </div>
      )}
    </div>
  );
};

export default OpenCodeProviderSection;
