import { memo, useState } from 'react';
import type { ClaudeContentBlock } from '../../types';
import { truncate } from '../../utils/helpers';
import { ToolBlockShell } from './ToolBlockShell';
import { BookIcon } from '../Icons';

type SkillUseBlock = Extract<ClaudeContentBlock, { type: 'skill_use' }>;

interface SkillBlockProps {
  block: SkillUseBlock;
}

const SkillBlock = memo(function SkillBlock({ block }: SkillBlockProps) {
  const [expanded, setExpanded] = useState(false);
  const titleContent = (
    <>
      <BookIcon size={16} className="tool-title-icon" />
      <span className="tool-title-text">Skill: {block.name}</span>
      <span className="tool-meta-badge">skill</span>
      {block.args && (
        <span className="task-summary-text tool-title-summary" title={block.args}>
          {truncate(block.args, 72)}
        </span>
      )}
    </>
  );

  return (
    <ToolBlockShell
      expanded={expanded}
      onToggle={() => setExpanded((prev) => !prev)}
      isCompleted={true}
      isError={false}
      titleContent={titleContent}
      className="skill-tool-card"
    >
      <div className="task-details">
        <div className="task-content-wrapper">
          <div className="task-field">
            <div className="task-field-label">name</div>
            <div className="task-field-content">{block.name}</div>
          </div>
          {block.command && (
            <div className="task-field">
              <div className="task-field-label">command</div>
              <div className="task-field-content">{block.command}</div>
            </div>
          )}
          {block.args && (
            <div className="task-field">
              <div className="task-field-label">args</div>
              <div className="task-field-content">{block.args}</div>
            </div>
          )}
          {block.source && (
            <div className="task-field">
              <div className="task-field-label">source</div>
              <div className="task-field-content">{block.source}</div>
            </div>
          )}
        </div>
      </div>
    </ToolBlockShell>
  );
});

export default SkillBlock;
