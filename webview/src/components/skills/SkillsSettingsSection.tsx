import { sendAction, subscribeEvent } from '../../bridge/typed';
import { BanIcon, CheckIcon, ChevronDownIcon, ChevronRightIcon, DownloadIcon, EditIcon, ExtensionsIcon, FolderIcon, GlobeIcon, PlusIcon, HelpIcon, RefreshIcon, SearchIcon, TrashIcon } from '../Icons';;
import { UPSTREAM, DOWNSTREAM } from '../../generated/protocol';
import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { Skill, SkillsConfig, SkillScope, SkillFilter, SkillEnabledFilter } from '../../types/skill';
import { registerLegacyAlias } from '../../bridge';
import { SkillHelpDialog } from './SkillHelpDialog';
import { SkillConfirmDialog } from './SkillConfirmDialog';
import { ToastContainer, type ToastMessage } from '../Toast';

interface SkillsSettingsSectionProps {
  currentProvider?: string;
}

function createEmptySkillsConfig(): SkillsConfig {
  return { global: {}, local: {}, user: {}, repo: {} };
}

function isSkillRecord(value: unknown): value is Record<string, Skill> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function normalizeSkillsConfig(value: unknown): SkillsConfig {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return createEmptySkillsConfig();
  }

  const candidate = value as Partial<SkillsConfig>;
  return {
    global: isSkillRecord(candidate.global) ? candidate.global : {},
    local: isSkillRecord(candidate.local) ? candidate.local : {},
    user: isSkillRecord(candidate.user) ? candidate.user : {},
    repo: isSkillRecord(candidate.repo) ? candidate.repo : {},
  };
}

/**
 * Skills settings component
 * Manages Claude/Codex Skills
 * Claude: global/local scopes, file-move enable/disable
 * Codex: user/repo scopes, config.toml enable/disable
 */
export function SkillsSettingsSection({ currentProvider = 'claude' }: SkillsSettingsSectionProps) {
  const { t } = useTranslation();
  // Skills data
  const [skills, setSkills] = useState<SkillsConfig>(createEmptySkillsConfig);
  const [loading, setLoading] = useState(true);
  const [expandedSkills, setExpandedSkills] = useState<Set<string>>(new Set());

  // UI state
  const [showDropdown, setShowDropdown] = useState(false);
  const [currentFilter, setCurrentFilter] = useState<SkillFilter>('all');
  const [enabledFilter, setEnabledFilter] = useState<SkillEnabledFilter>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Dialog state
  const [showHelpDialog, setShowHelpDialog] = useState(false);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);
  const [deletingSkill, setDeletingSkill] = useState<Skill | null>(null);

  // Skills currently being toggled (used to disable buttons and prevent duplicate clicks)
  const [togglingSkills, setTogglingSkills] = useState<Set<string>>(new Set());

  // Toast state
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  // Toast helper functions
  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `toast-${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);

  const dismissToast = (id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  };

  const isCodex = currentProvider === 'codex';

  // Compute Skills lists (provider-aware: Claude uses global/local, Codex uses user/repo)
  const primarySkillList = useMemo(
    () => Object.values(isCodex ? (skills.user ?? {}) : skills.global),
    [isCodex, skills.global, skills.user]
  );
  const secondarySkillList = useMemo(
    () => Object.values(isCodex ? (skills.repo ?? {}) : skills.local),
    [isCodex, skills.local, skills.repo]
  );
  const allSkillList = useMemo(() => [...primarySkillList, ...secondarySkillList], [primarySkillList, secondarySkillList]);

  // Filtered Skills list
  const filteredSkills = useMemo(() => {
    let list: Skill[] = [];
    if (currentFilter === 'all') {
      list = allSkillList;
    } else if (currentFilter === 'global' || currentFilter === 'user') {
      list = primarySkillList;
    } else {
      list = secondarySkillList;
    }

    // Filter by enabled status
    if (enabledFilter === 'enabled') {
      list = list.filter(s => s.enabled);
    } else if (enabledFilter === 'disabled') {
      list = list.filter(s => !s.enabled);
    }

    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      list = list.filter(s =>
        s.name.toLowerCase().includes(query) ||
        s.path.toLowerCase().includes(query) ||
        (s.description && s.description.toLowerCase().includes(query))
      );
    }

    // Sort by enabled status: enabled first
    return [...list].sort((a, b) => {
      if (a.enabled === b.enabled) return 0;
      return a.enabled ? -1 : 1;
    });
  }, [currentFilter, enabledFilter, searchQuery, allSkillList, primarySkillList, secondarySkillList]);

  // Counts
  const totalCount = allSkillList.length;
  const primaryCount = primarySkillList.length;
  const secondaryCount = secondarySkillList.length;
  const { enabledCount, disabledCount } = useMemo(() => {
    let enabled = 0;
    for (const s of allSkillList) if (s.enabled) enabled++;
    return { enabledCount: enabled, disabledCount: allSkillList.length - enabled };
  }, [allSkillList]);

  // Icon colors
  const iconColors = [
    '#3B82F6', '#10B981', '#8B5CF6', '#F59E0B',
    '#EF4444', '#EC4899', '#06B6D4', '#6366F1',
  ];

  const getIconColor = (skillId: string): string => {
    let hash = 0;
    for (let i = 0; i < skillId.length; i++) {
      hash = skillId.charCodeAt(i) + ((hash << 5) - hash);
    }
    return iconColors[Math.abs(hash) % iconColors.length];
  };

  const getSkillIconStyle = (skillId: string, enabled: boolean): React.CSSProperties => ({
    color: enabled ? getIconColor(skillId) : 'var(--text-tertiary)',
  });

  const loadSkills = useCallback(() => {
    setLoading(true);
    sendAction(UPSTREAM.GET_ALL_SKILLS, {});
  }, []);

  // Initialization（[归一化] 经 bridgeHub 订阅,替代旧 window.xxx 覆盖）
  useEffect(() => {
    registerLegacyAlias('updateSkills', DOWNSTREAM.SKILL_LIST);
    registerLegacyAlias('skillImportResult', DOWNSTREAM.SKILL_IMPORT_RESULT);
    registerLegacyAlias('skillDeleteResult', DOWNSTREAM.SKILL_DELETE_RESULT);
    registerLegacyAlias('skillToggleResult', DOWNSTREAM.SKILL_TOGGLE_RESULT);

    const unsubs: Array<() => void> = [];

    // Java side returns Skills list
    unsubs.push(subscribeEvent(DOWNSTREAM.SKILL_LIST, (jsonStr) => {
      try {
        const data = normalizeSkillsConfig(JSON.parse(jsonStr as string));
        setSkills(data);
        setLoading(false);

      } catch (error) {
        console.error('[SkillsSettings] Failed to parse skills:', error);
        setLoading(false);
      }
    }));

    // import result
    unsubs.push(subscribeEvent(DOWNSTREAM.SKILL_IMPORT_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        if (result.success) {
          const count = result.count || 0;
          const total = result.total || 0;
          if (result.errors && result.errors.length > 0) {
            addToast(t('skills.importPartialSuccess', { count, total }), 'warning');
          } else if (count === 1) {
            addToast(t('skills.importSuccessOne'), 'success');
          } else if (count > 1) {
            addToast(t('skills.importSuccess', { count }), 'success');
          }
          // Reload
          loadSkills();
        } else {
          addToast(result.error || t('skills.importFailed'), 'error');
        }
      } catch (error) {
        console.error('[SkillsSettings] Failed to parse import result:', error);
      }
    }));

    // delete result
    unsubs.push(subscribeEvent(DOWNSTREAM.SKILL_DELETE_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        if (result.success) {
          addToast(t('skills.deleteSuccess'), 'success');
          loadSkills();
        } else {
          addToast(result.error || t('skills.deleteFailed'), 'error');
        }
      } catch (error) {
        console.error('[SkillsSettings] Failed to parse delete result:', error);
      }
    }));

    // enable/disable result
    unsubs.push(subscribeEvent(DOWNSTREAM.SKILL_TOGGLE_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        // Remove in-progress state
        setTogglingSkills(prev => {
          const newSet = new Set(prev);
          if (result.name) {
            // Try to remove possible ID variants
            newSet.forEach(id => {
              if (id.includes(result.name)) {
                newSet.delete(id);
              }
            });
          }
          return newSet;
        });

        if (result.success) {
          addToast(result.enabled ? t('skills.enableSuccess', { name: result.name }) : t('skills.disableSuccess', { name: result.name }), 'success');
          loadSkills();
        } else {
          if (result.conflict) {
            addToast(t('skills.operationFailed', { error: result.error }), 'warning');
          } else {
            addToast(result.error || t('skills.operationError'), 'error');
          }
        }
      } catch (error) {
        console.error('[SkillsSettings] Failed to parse toggle result:', error);
        setTogglingSkills(new Set()); // Clear on error
      }
    }));

    // Load Skills
    loadSkills();

    // Close dropdown when clicking outside
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setShowDropdown(false);
      }
    };
    document.addEventListener('click', handleClickOutside);

    return () => {
      unsubs.forEach((u) => u());
      document.removeEventListener('click', handleClickOutside);
    };
  }, [loadSkills, addToast]);

  // Auto-refresh when provider changes (skip initial mount — handled by init useEffect above)
  const isInitialMount = useRef(true);
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }
    setCurrentFilter('all');
    loadSkills();
  }, [currentProvider, loadSkills]);

  // Toggle expand state (accordion behavior)
  const toggleExpand = (skillId: string) => {
    const newExpanded = new Set<string>();
    if (!expandedSkills.has(skillId)) {
      newExpanded.add(skillId);
    }
    setExpandedSkills(newExpanded);
  };

  // Refresh
  const handleRefresh = () => {
    loadSkills();
    addToast(t('skills.refreshed'), 'success');
  };

  // Import Skill
  const handleImport = (scope: SkillScope) => {
    setShowDropdown(false);
    sendAction(UPSTREAM.IMPORT_SKILL, { scope });
  };

  // Get the primary/secondary scope values based on provider
  const primaryScope: SkillScope = isCodex ? 'user' : 'global';
  const secondaryScope: SkillScope = isCodex ? 'repo' : 'local';

  // Open in editor
  const handleOpen = (skill: Skill) => {
    sendAction(UPSTREAM.OPEN_SKILL, { path: skill.path });
  };

  // Delete Skill
  const handleDelete = (skill: Skill) => {
    setDeletingSkill(skill);
    setShowConfirmDialog(true);
  };

  // Confirm deletion
  const confirmDelete = () => {
    if (deletingSkill) {
      sendAction(UPSTREAM.DELETE_SKILL, {
        name: deletingSkill.name,
        scope: deletingSkill.scope,
        enabled: deletingSkill.enabled,
        ...(isCodex && deletingSkill.skillPath ? { skillPath: deletingSkill.skillPath } : {}),
      });
      setExpandedSkills((prev) => {
        const newSet = new Set(prev);
        newSet.delete(deletingSkill.id);
        return newSet;
      });
    }
    setShowConfirmDialog(false);
    setDeletingSkill(null);
  };

  // Cancel deletion
  const cancelDelete = () => {
    setShowConfirmDialog(false);
    setDeletingSkill(null);
  };

  // Enable/disable Skill
  const handleToggle = (skill: Skill, e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent triggering card expand
    if (togglingSkills.has(skill.id)) return; // Prevent duplicate clicks

    setTogglingSkills(prev => new Set(prev).add(skill.id));
    sendAction(UPSTREAM.TOGGLE_SKILL, {
      name: skill.name,
      scope: skill.scope,
      enabled: skill.enabled,
      ...(isCodex && skill.skillPath ? { skillPath: skill.skillPath } : {}),
    });
  };

  // Scope label mapping for readable badge text
  const scopeLabelMap: Record<string, string> = {
    user: t('skills.user'),
    repo: t('skills.repo'),
    global: t('chat.global'),
    local: t('chat.localProject'),
  };

  return (
    <div className="skills-settings-section">
      {/* Toolbar */}
      <div className="skills-toolbar">
        {/* Filter tabs */}
        <div className="filter-tabs" role="tablist">
          <div
            className={`tab-item ${currentFilter === 'all' ? 'active' : ''}`}
            role="tab"
            tabIndex={0}
            aria-selected={currentFilter === 'all'}
            onClick={() => setCurrentFilter('all')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setCurrentFilter('all'); } }}
          >
            {t('skills.all')} <span className="count-badge">{totalCount}</span>
          </div>
          <div
            className={`tab-item ${currentFilter === (isCodex ? 'user' : 'global') ? 'active' : ''}`}
            role="tab"
            tabIndex={0}
            aria-selected={currentFilter === (isCodex ? 'user' : 'global')}
            onClick={() => setCurrentFilter(isCodex ? 'user' : 'global')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setCurrentFilter(isCodex ? 'user' : 'global'); } }}
          >
            {isCodex ? t('skills.user') : t('skills.global')} <span className="count-badge">{primaryCount}</span>
          </div>
          <div
            className={`tab-item ${currentFilter === (isCodex ? 'repo' : 'local') ? 'active' : ''}`}
            role="tab"
            tabIndex={0}
            aria-selected={currentFilter === (isCodex ? 'repo' : 'local')}
            onClick={() => setCurrentFilter(isCodex ? 'repo' : 'local')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setCurrentFilter(isCodex ? 'repo' : 'local'); } }}
          >
            {isCodex ? t('skills.repo') : t('skills.local')} <span className="count-badge">{secondaryCount}</span>
          </div>
          {/* Enabled status filter */}
          <div className="filter-separator"></div>
          <div
            className={`tab-item enabled-filter ${enabledFilter === 'enabled' ? 'active' : ''}`}
            role="tab"
            tabIndex={0}
            aria-selected={enabledFilter === 'enabled'}
            onClick={() => setEnabledFilter(enabledFilter === 'enabled' ? 'all' : 'enabled')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setEnabledFilter(enabledFilter === 'enabled' ? 'all' : 'enabled'); } }}
            title={t('skills.filterEnabled')}
          >
            <CheckIcon size={16} />
            {t('skills.enabled')} <span className="count-badge">{enabledCount}</span>
          </div>
          <div
            className={`tab-item enabled-filter ${enabledFilter === 'disabled' ? 'active' : ''}`}
            role="tab"
            tabIndex={0}
            aria-selected={enabledFilter === 'disabled'}
            onClick={() => setEnabledFilter(enabledFilter === 'disabled' ? 'all' : 'disabled')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setEnabledFilter(enabledFilter === 'disabled' ? 'all' : 'disabled'); } }}
            title={t('skills.filterDisabled')}
          >
            <BanIcon size={16} />
            {t('skills.disabled')} <span className="count-badge">{disabledCount}</span>
          </div>
        </div>

        {/* Right-side tools */}
        <div className="toolbar-right">
          {/* Search box */}
          <div className="search-box">
            <SearchIcon size={16} />
            <input
              type="text"
              className="search-input"
              placeholder={t('skills.searchPlaceholder')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          {/* Help button */}
          <button
            className="icon-btn"
            onClick={() => setShowHelpDialog(true)}
            title={t('skills.whatIsSkills')}
          >
            <HelpIcon size={16} />
          </button>

          {/* Import button */}
          <div className="add-dropdown" ref={dropdownRef}>
            <button
              className="icon-btn primary"
              onClick={() => setShowDropdown(!showDropdown)}
              title={t('skills.importSkill')}
            >
              <PlusIcon size={16} />
            </button>
            {showDropdown && (
              <div className="dropdown-menu">
                <div className="dropdown-item" onClick={() => handleImport(primaryScope)}>
                  <GlobeIcon size={16} />
                  {isCodex ? t('skills.importUserSkill') : t('skills.importGlobalSkill')}
                </div>
                <div className="dropdown-item" onClick={() => handleImport(secondaryScope)}>
                  <DownloadIcon size={16} />
                  {isCodex ? t('skills.importRepoSkill') : t('skills.importLocalSkill')}
                </div>
              </div>
            )}
          </div>

          {/* Refresh button */}
          <button
            className="icon-btn"
            onClick={handleRefresh}
            disabled={loading}
            title={t('chat.refresh')}
          >
            <RefreshIcon size={16} className={loading ? 'spinning' : ''} />
          </button>
        </div>
      </div>

      {/* Skills list */}
      <div className="skill-list">
        {filteredSkills.map((skill) => (
          <div
            key={skill.id}
            className={`skill-card ${expandedSkills.has(skill.id) ? 'expanded' : ''} ${!skill.enabled ? 'disabled' : ''}`}
          >
            {/* Card header */}
            <div className="card-header" onClick={() => toggleExpand(skill.id)}>
              {/* Enable/disable toggle */}
              <button
                className={`toggle-switch ${skill.enabled ? 'enabled' : 'disabled'} ${togglingSkills.has(skill.id) ? 'loading' : ''}`}
                onClick={(e) => handleToggle(skill, e)}
                disabled={togglingSkills.has(skill.id)}
                title={skill.enabled ? t('chat.clickToDisable') : t('chat.clickToEnable')}
              >
                {togglingSkills.has(skill.id) ? (
                  <span className="codicon codicon-loading codicon-modifier-spin"></span>
                ) : skill.enabled ? (
                  <CheckIcon size={16} />
                ) : (
                  <BanIcon size={16} />
                )}
              </button>

              <div className="skill-icon-wrapper" style={getSkillIconStyle(skill.id, skill.enabled)}>
                <FolderIcon size={16} />
              </div>

              <div className="skill-info">
                <div className="skill-header-row">
                  <span className={`skill-name ${!skill.enabled ? 'muted' : ''}`}>{skill.name}</span>
                  <span className={`scope-badge ${skill.scope}`}>
                    {(skill.scope === 'global' || skill.scope === 'user') ? <GlobeIcon size={14} /> : <DownloadIcon size={14} />}
                    {scopeLabelMap[skill.scope] || skill.scope}
                  </span>
                  {!skill.enabled && (
                    <span className="status-badge disabled">
                      {t('chat.disabled')}
                    </span>
                  )}
                </div>
                <div className="skill-path" title={skill.path}>{skill.path}</div>
              </div>

              <div className="expand-indicator">
                {expandedSkills.has(skill.id) ? <ChevronDownIcon size={14} /> : <ChevronRightIcon size={14} />}
              </div>
            </div>

            {/* Expanded content */}
            {expandedSkills.has(skill.id) && (
              <div className="card-content">
                <div className="info-section">
                  {skill.description ? (
                    <div className="description-container">
                      <div className="description-label">{t('skills.description')}:</div>
                      <div className="description-content">{skill.description}</div>
                    </div>
                  ) : (
                    <div className="description-placeholder">{t('skills.noDescription')}</div>
                  )}
                </div>

                <div className="actions-section">
                  <button className="action-btn edit-btn" onClick={() => handleOpen(skill)}>
                    <EditIcon size={16} /> {t('common.edit')}
                  </button>
                  <button className="action-btn delete-btn" onClick={() => handleDelete(skill)}>
                    <TrashIcon size={16} /> {t('common.delete')}
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}

        {/* Empty state */}
        {filteredSkills.length === 0 && !loading && (
          <div className="empty-state">
            <ExtensionsIcon size={16} />
            <p>{t('skills.noMatchingSkills')}</p>
            <p className="hint">{t('skills.importHint')}</p>
          </div>
        )}

        {/* Loading state */}
        {loading && filteredSkills.length === 0 && (
          <div className="loading-state">
            <span className="codicon codicon-loading codicon-modifier-spin"></span>
            <p>{t('common.loading')}</p>
          </div>
        )}
      </div>

      {/* Dialogs */}
      {showHelpDialog && (
        <SkillHelpDialog onClose={() => setShowHelpDialog(false)} currentProvider={currentProvider} />
      )}

      {showConfirmDialog && deletingSkill && (
        <SkillConfirmDialog
          title={t('skills.deleteTitle')}
          message={t('skills.deleteMessage', {
            scope: isCodex
              ? ((deletingSkill.scope === 'user') ? t('skills.deleteMessageUser') : t('skills.deleteMessageRepo'))
              : ((deletingSkill.scope === 'global') ? t('skills.deleteMessageGlobal') : t('skills.deleteMessageLocal')),
            name: deletingSkill.name
          })}
          confirmText={t('common.delete')}
          cancelText={t('common.cancel')}
          onConfirm={confirmDelete}
          onCancel={cancelDelete}
        />
      )}

      {/* Toast notifications */}
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}
