import { useTranslation } from 'react-i18next';
import { McpSettingsSection } from '../../mcp/McpSettingsSection';
import { ShieldIcon, ServerIcon, RobotIcon, BookIcon } from '../../Icons';
import styles from './style.module.less';

interface PlaceholderSectionProps {
  type: 'permissions' | 'mcp' | 'agents' | 'skills';
  currentProvider?: 'claude' | 'codex' | string;
}

const PlaceholderSection = ({ type, currentProvider }: PlaceholderSectionProps) => {
  const { t } = useTranslation();

  const sectionConfig = {
    permissions: {
      title: t('settings.permissions'),
      desc: currentProvider === 'opencode' ? t('settings.openCodePermissionsDesc') : t('settings.permissionsDesc'),
      IconComponent: ShieldIcon,
      message: currentProvider === 'opencode' ? t('settings.openCodePermissionNotice') : t('settings.permissionsComingSoon'),
    },
    mcp: {
      title: t('settings.mcp'),
      desc: t('settings.mcpDesc'),
      IconComponent: ServerIcon,
      message: null, // MCP has its own dedicated component
    },
    agents: {
      title: t('settings.agents'),
      desc: t('settings.agentsDesc'),
      IconComponent: RobotIcon,
      message: t('settings.agentsComingSoon'),
    },
    skills: {
      title: t('settings.skills'),
      desc: t('settings.skillsDesc'),
      IconComponent: BookIcon,
      message: t('settings.skillsComingSoon'),
    },
  };

  const config = sectionConfig[type];

  return (
    <div className={styles.configSection}>
      <h3 className={styles.sectionTitle}>{config.title}</h3>
      <p className={styles.sectionDesc}>{config.desc}</p>

      {type === 'mcp' ? (
        <McpSettingsSection />
      ) : (
        <div className={styles.tempNotice}>
          <config.IconComponent size={16} />
          <p>{config.message}</p>
        </div>
      )}
    </div>
  );
};

export default PlaceholderSection;
