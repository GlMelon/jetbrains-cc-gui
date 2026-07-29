import { sendAction, subscribeEvent } from '../../bridge/typed';
import {
  BanIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CodeIcon,
  DownloadIcon,
  EditIcon,
  ExtensionsIcon,
  FileCodeIcon,
  FileTextIcon,
  FolderIcon,
  GlobeIcon,
  LightbulbIcon,
  RefreshIcon,
  RocketIcon,
  SearchIcon,
  SparklesIcon,
  TerminalIcon,
  TrashIcon,
  ZapIcon,
} from '../Icons';
import { UPSTREAM, DOWNSTREAM } from '../../generated/protocol';
import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { SKILL_SCOPE } from '../../types/skill';
import type {
  Skill,
  SkillsConfig,
  SkillScope,
  SkillFilter,
  SkillEnabledFilter,
  SkillDocumentResult,
  SkillDocumentSavePayload,
} from '../../types/skill';
import { registerLegacyAlias } from '../../bridge';
import { SkillHelpDialog } from './SkillHelpDialog';
import { SkillConfirmDialog } from './SkillConfirmDialog';
import { SkillMarketDialog } from './SkillMarketDialog';
import { SkillEditorDialog } from './SkillEditorDialog';
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
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [skillDocument, setSkillDocument] = useState<SkillDocumentResult | null>(null);
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorSaving, setEditorSaving] = useState(false);
  const editorRequestSequence = useRef(0);
  const activeEditorRequestId = useRef<string | null>(null);

  // Skills market dialog (从市场安装:GitHub 仓库 tarball 下载)
  const [showMarketDialog, setShowMarketDialog] = useState(false);

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
  const primaryScope: SkillScope = isCodex ? SKILL_SCOPE.USER : SKILL_SCOPE.GLOBAL;
  const secondaryScope: SkillScope = isCodex ? SKILL_SCOPE.REPO : SKILL_SCOPE.LOCAL;

  // Compute Skills lists (provider-aware: Claude uses global/local, Codex uses user/repo)
  const primarySkillList = useMemo(
    () => Object.values(isCodex ? (skills.user ?? {}) : skills.global),
    [isCodex, skills.global, skills.user],
  );
  const secondarySkillList = useMemo(
    () => Object.values(isCodex ? (skills.repo ?? {}) : skills.local),
    [isCodex, skills.local, skills.repo],
  );
  const allSkillList = useMemo(
    () => [...primarySkillList, ...secondarySkillList],
    [primarySkillList, secondarySkillList],
  );

  // Filtered Skills list
  const filteredSkills = useMemo(() => {
    let list: Skill[] =
      currentFilter === 'all'
        ? allSkillList
        : currentFilter === SKILL_SCOPE.GLOBAL || currentFilter === SKILL_SCOPE.USER
          ? primarySkillList
          : secondarySkillList;

    // Filter by enabled status
    if (enabledFilter === 'enabled') {
      list = list.filter((s) => s.enabled);
    } else if (enabledFilter === 'disabled') {
      list = list.filter((s) => !s.enabled);
    }

    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      list = list.filter(
        (s) =>
          s.name.toLowerCase().includes(query) ||
          s.path.toLowerCase().includes(query) ||
          (s.description && s.description.toLowerCase().includes(query)),
      );
    }

    // Sort by enabled status: enabled first
    return [...list].sort((a, b) => {
      if (a.enabled === b.enabled) return 0;
      return a.enabled ? -1 : 1;
    });
  }, [
    currentFilter,
    enabledFilter,
    searchQuery,
    allSkillList,
    primarySkillList,
    secondarySkillList,
  ]);

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
    '#3B82F6',
    '#10B981',
    '#8B5CF6',
    '#F59E0B',
    '#EF4444',
    '#EC4899',
    '#06B6D4',
    '#6366F1',
  ];

  const getIconColor = (skillId: string): string => {
    let hash = 0;
    for (let i = 0; i < skillId.length; i++) {
      hash = skillId.charCodeAt(i) + ((hash << 5) - hash);
    }
    return iconColors[Math.abs(hash) % iconColors.length];
  };

  /** 按 skill 名关键词匹配辨识图标,未命中回退 FolderIcon */
  const getSkillIcon = (name: string): React.ReactElement => {
    const n = name.toLowerCase();
    if (/(code|refactor|lint|format)/.test(n)) return <CodeIcon size={16} />;
    if (/(terminal|shell|bash|cmd)/.test(n)) return <TerminalIcon size={16} />;
    if (/(doc|readme|markdown|note)/.test(n)) return <FileTextIcon size={16} />;
    if (/(spark|ai|brain|gen)/.test(n)) return <SparklesIcon size={16} />;
    if (/(idea|light|tip|hint)/.test(n)) return <LightbulbIcon size={16} />;
    if (/(deploy|ship|release|publish)/.test(n)) return <RocketIcon size={16} />;
    if (/(file|script)/.test(n)) return <FileCodeIcon size={16} />;
    return <FolderIcon size={16} />;
  };

  const loadSkills = useCallback(() => {
    setLoading(true);
    sendAction(UPSTREAM.GET_ALL_SKILLS, {});
  }, []);

  // Initialization（[归一化] 经 bridgeHub 订阅,替代旧 window.xxx 覆盖）
  useEffect(() => {
    registerLegacyAlias('updateSkills', DOWNSTREAM.SKILL_LIST);
    registerLegacyAlias('skillImportResult', DOWNSTREAM.SKILL_IMPORT_RESULT);
    registerLegacyAlias('skillDeleteResult', DOWNSTREAM.SKILL_DELETE_RESULT);
    registerLegacyAlias('skillDocument', DOWNSTREAM.SKILL_DOCUMENT);
    registerLegacyAlias('skillSaveResult', DOWNSTREAM.SKILL_SAVE_RESULT);
    registerLegacyAlias('skillToggleResult', DOWNSTREAM.SKILL_TOGGLE_RESULT);

    const unsubs: Array<() => void> = [];

    // Java side returns Skills list
    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_LIST, (jsonStr) => {
        try {
          const data = normalizeSkillsConfig(JSON.parse(jsonStr as string));
          setSkills(data);
          setLoading(false);
        } catch (error) {
          console.error('[SkillsSettings] Failed to parse skills:', error);
          setLoading(false);
        }
      }),
    );

    // import result
    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_IMPORT_RESULT, (jsonStr) => {
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
      }),
    );

    // delete result
    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_DELETE_RESULT, (jsonStr) => {
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
      }),
    );

    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_DOCUMENT, (payload) => {
        try {
          const result = (
            typeof payload === 'string' ? JSON.parse(payload) : payload
          ) as SkillDocumentResult;
          if (!result || result.requestId !== activeEditorRequestId.current) {
            return;
          }
          setSkillDocument(result);
          setEditorLoading(false);
          if (!result.success) {
            addToast(result.error || t('skills.editor.loadFailed'), 'error');
          }
        } catch (error) {
          console.error('[SkillsSettings] Failed to parse skill document:', error);
          setEditorLoading(false);
          addToast(t('skills.editor.loadFailed'), 'error');
        }
      }),
    );

    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_SAVE_RESULT, (payload) => {
        try {
          const result = (
            typeof payload === 'string' ? JSON.parse(payload) : payload
          ) as SkillDocumentResult;
          if (!result || result.requestId !== activeEditorRequestId.current) {
            return;
          }
          setEditorSaving(false);
          if (result.success) {
            addToast(
              result.changed === false
                ? t('skills.editor.noChanges')
                : t('skills.editor.saveSuccess'),
              'success',
            );
            activeEditorRequestId.current = null;
            setEditingSkill(null);
            setSkillDocument(null);
            loadSkills();
            return;
          }
          setSkillDocument((current) =>
            current
              ? {
                  ...current,
                  conflict: result.conflict,
                  parseError: result.parseError,
                  error: result.error,
                }
              : result,
          );
          addToast(
            result.conflict
              ? t('skills.editor.conflictToast')
              : result.error || t('skills.editor.saveFailed'),
            result.conflict ? 'warning' : 'error',
          );
        } catch (error) {
          console.error('[SkillsSettings] Failed to parse skill save result:', error);
          setEditorSaving(false);
          addToast(t('skills.editor.saveFailed'), 'error');
        }
      }),
    );

    // enable/disable result
    unsubs.push(
      subscribeEvent(DOWNSTREAM.SKILL_TOGGLE_RESULT, (jsonStr) => {
        try {
          const result = JSON.parse(jsonStr as string);
          // Remove in-progress state
          setTogglingSkills((prev) => {
            const newSet = new Set(prev);
            if (result.name) {
              // Try to remove possible ID variants
              newSet.forEach((id) => {
                if (id.includes(result.name)) {
                  newSet.delete(id);
                }
              });
            }
            return newSet;
          });

          if (result.success) {
            addToast(
              result.enabled
                ? t('skills.enableSuccess', { name: result.name })
                : t('skills.disableSuccess', { name: result.name }),
              'success',
            );
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
      }),
    );

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
  }, [loadSkills, addToast, t]);

  // Auto-refresh when provider changes (skip initial mount — handled by init useEffect above)
  const isInitialMount = useRef(true);
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }
    setCurrentFilter('all');
    activeEditorRequestId.current = null;
    setEditingSkill(null);
    setSkillDocument(null);
    setEditorLoading(false);
    setEditorSaving(false);
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

  // Open in editor

  const skillDocumentIdentity = (skill: Skill) => ({
    scope: skill.scope,
    name: skill.name,
    directoryPath: skill.path,
    skillPath: skill.skillPath ?? skill.path,
    enabled: skill.enabled,
  });

  const nextEditorRequestId = (skill: Skill) => {
    editorRequestSequence.current += 1;
    return `${skill.id}:${editorRequestSequence.current}`;
  };

  const loadSkillDocument = (skill: Skill) => {
    const requestId = nextEditorRequestId(skill);
    activeEditorRequestId.current = requestId;
    setEditingSkill(skill);
    setSkillDocument(null);
    setEditorLoading(true);
    setEditorSaving(false);
    if (
      !sendAction(UPSTREAM.GET_SKILL_DOCUMENT, {
        requestId,
        ...skillDocumentIdentity(skill),
      })
    ) {
      setEditorLoading(false);
      addToast(t('skills.editor.loadFailed'), 'error');
    }
  };

  const handleEdit = (skill: Skill) => {
    loadSkillDocument(skill);
  };

  const handleOpen = (skill: Skill) => {
    sendAction(UPSTREAM.OPEN_SKILL, { path: skill.path });
  };

  const handleCloseEditor = () => {
    activeEditorRequestId.current = null;
    setEditingSkill(null);
    setSkillDocument(null);
    setEditorLoading(false);
    setEditorSaving(false);
  };

  const handleSaveDocument = (payload: SkillDocumentSavePayload) => {
    if (!editingSkill || !skillDocument?.revision) {
      return;
    }
    const requestId = nextEditorRequestId(editingSkill);
    activeEditorRequestId.current = requestId;
    setEditorSaving(true);
    if (
      !sendAction(UPSTREAM.SAVE_SKILL_DOCUMENT, {
        requestId,
        ...skillDocumentIdentity(editingSkill),
        revision: skillDocument.revision,
        changes: payload.changes,
        body: payload.body,
      })
    ) {
      setEditorSaving(false);
      addToast(t('skills.editor.saveFailed'), 'error');
    }
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

    setTogglingSkills((prev) => new Set(prev).add(skill.id));
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
      {/* Header(设计稿 panel-header) */}
      <div className="panel-header">
        <div className="panel-title">
          <span className="ico-badge">
            <ZapIcon size={16} />
          </span>
          <span className="title-text">
            {t('skills.title')}
            <span className="subtitle">{t('skills.subtitle')}</span>
          </span>
        </div>
        <div className="header-tools">
          {/* 帮助:什么是 Skills? */}
          <button
            className="icon-btn"
            onClick={() => setShowHelpDialog(true)}
            title={t('skills.whatIsSkills')}
            aria-label={t('skills.whatIsSkills')}
          >
            ?
          </button>
          <button
            className="icon-btn"
            onClick={handleRefresh}
            disabled={loading}
            title={t('chat.refresh')}
          >
            <RefreshIcon size={16} className={loading ? 'spinning' : ''} />
          </button>
          <div className="add-dropdown" ref={dropdownRef}>
            <button
              className="btn-ghost"
              onClick={() => setShowDropdown(!showDropdown)}
              title={t('skills.importSkill')}
            >
              <DownloadIcon size={16} />
              {t('skills.importSkill')}
              <ChevronDownIcon size={16} />
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
          <button
            className="market-btn"
            onClick={() => setShowMarketDialog(true)}
            title={t('skills.fromMarket')}
          >
            <ExtensionsIcon size={16} />
            {t('skills.fromMarket')}
          </button>
        </div>
      </div>

      {/* 工具栏(设计稿:分段控件 + 胶囊筛选 + 搜索) */}
      <div className="skills-toolbar">
        <div className="seg-group" role="group">
          <button
            className={currentFilter === 'all' ? 'active' : ''}
            onClick={() => setCurrentFilter('all')}
          >
            {t('skills.all')} <span className="count">{totalCount}</span>
          </button>
          <button
            className={currentFilter === primaryScope ? 'active' : ''}
            onClick={() => setCurrentFilter(primaryScope)}
          >
            {isCodex ? t('skills.user') : t('skills.global')}{' '}
            <span className="count">{primaryCount}</span>
          </button>
          <button
            className={currentFilter === secondaryScope ? 'active' : ''}
            onClick={() => setCurrentFilter(secondaryScope)}
          >
            {isCodex ? t('skills.repo') : t('skills.local')}{' '}
            <span className="count">{secondaryCount}</span>
          </button>
        </div>

        <span className="toolbar-divider" />

        <button
          className={`filter-pill ${enabledFilter === 'enabled' ? 'active' : ''}`}
          onClick={() => setEnabledFilter(enabledFilter === 'enabled' ? 'all' : 'enabled')}
          title={t('skills.filterEnabled')}
        >
          <CheckIcon size={14} />
          {t('skills.enabled')} <span className="count">{enabledCount}</span>
        </button>
        <button
          className={`filter-pill ${enabledFilter === 'disabled' ? 'active' : ''}`}
          onClick={() => setEnabledFilter(enabledFilter === 'disabled' ? 'all' : 'disabled')}
          title={t('skills.filterDisabled')}
        >
          <BanIcon size={14} />
          {t('skills.disabled')} <span className="count">{disabledCount}</span>
        </button>

        <span className="toolbar-spacer" />

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
      </div>

      {/* Skills list */}
      <div className="skill-list">
        {filteredSkills.map((skill) => {
          const isGlobal = skill.scope === SKILL_SCOPE.GLOBAL || skill.scope === SKILL_SCOPE.USER;
          return (
            <div
              key={skill.id}
              className={`item-card ${expandedSkills.has(skill.id) ? 'expanded' : ''} ${!skill.enabled ? 'disabled' : ''}`}
            >
              {/* 卡片行 */}
              <div className="card-row" onClick={() => toggleExpand(skill.id)}>
                <button
                  className={`enable-btn ${skill.enabled ? 'enabled' : ''}`}
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

                <div
                  className="card-icon codicon-icon"
                  style={{
                    background: skill.enabled ? getIconColor(skill.id) : 'var(--bg-tertiary)',
                  }}
                >
                  {getSkillIcon(skill.name)}
                </div>

                <div className="card-main">
                  <div className="card-title">
                    <span className={!skill.enabled ? 'muted-name' : ''}>{skill.name}</span>
                  </div>
                  {skill.description ? (
                    <div className="card-desc" title={skill.description}>
                      {skill.description}
                    </div>
                  ) : (
                    <div className="card-desc" style={{ opacity: 0.6, fontStyle: 'italic' }}>
                      {t('skills.noDescription')}
                    </div>
                  )}
                  <div className="card-meta">
                    <span className={`pill ${isGlobal ? 'remote' : 'local'}`}>
                      {isGlobal ? <GlobeIcon size={14} /> : <DownloadIcon size={14} />}
                      {scopeLabelMap[skill.scope] || skill.scope}
                    </span>
                    <span className="pill muted mono" title={skill.path}>
                      {skill.path}
                    </span>
                    {!skill.enabled && <span className="pill muted">{t('chat.disabled')}</span>}
                  </div>
                </div>

                <span className="chev">
                  <ChevronRightIcon size={16} />
                </span>
              </div>

              {/* 展开内容 */}
              {expandedSkills.has(skill.id) && (
                <div className="card-expand">
                  <div className="expand-grid">
                    <span className="k">{t('skills.path')}</span>
                    <span className="v code">{skill.path}</span>
                    {skill.description && (
                      <>
                        <span className="k">{t('skills.description')}</span>
                        <span className="v">{skill.description}</span>
                      </>
                    )}
                  </div>
                  <div className="expand-actions">
                    <button className="btn-ghost" onClick={() => handleEdit(skill)}>
                      <EditIcon size={16} /> {t('common.edit')}
                    </button>
                    <button className="btn-ghost" onClick={() => handleOpen(skill)}>
                      <FileCodeIcon size={16} /> {t('skills.editor.openInIde')}
                    </button>
                    <button className="btn-ghost" onClick={() => handleDelete(skill)}>
                      <TrashIcon size={16} /> {t('common.delete')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}

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
      {editingSkill && (
        <SkillEditorDialog
          isOpen={true}
          skillName={editingSkill.name}
          loading={editorLoading}
          saving={editorSaving}
          document={skillDocument}
          onClose={handleCloseEditor}
          onReload={() => loadSkillDocument(editingSkill)}
          onOpenInIde={() => handleOpen(editingSkill)}
          onSave={handleSaveDocument}
        />
      )}

      {showHelpDialog && (
        <SkillHelpDialog
          onClose={() => setShowHelpDialog(false)}
          currentProvider={currentProvider}
        />
      )}

      {showConfirmDialog && deletingSkill && (
        <SkillConfirmDialog
          title={t('skills.deleteTitle')}
          message={t('skills.deleteMessage', {
            scope: isCodex
              ? deletingSkill.scope === SKILL_SCOPE.USER
                ? t('skills.deleteMessageUser')
                : t('skills.deleteMessageRepo')
              : deletingSkill.scope === SKILL_SCOPE.GLOBAL
                ? t('skills.deleteMessageGlobal')
                : t('skills.deleteMessageLocal'),
            name: deletingSkill.name,
          })}
          confirmText={t('common.delete')}
          cancelText={t('common.cancel')}
          onConfirm={confirmDelete}
          onCancel={cancelDelete}
        />
      )}

      {/* Skills market dialog (从市场安装) */}
      {showMarketDialog && (
        <SkillMarketDialog
          currentProvider={currentProvider}
          onClose={() => setShowMarketDialog(false)}
          onInstalled={loadSkills}
        />
      )}

      {/* Toast notifications */}
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}
