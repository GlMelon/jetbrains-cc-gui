import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SkillDocumentField, SkillDocumentResult } from '../../../src/types/skill';
import { SkillEditorDialog } from '../../../src/components/skills/SkillEditorDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) =>
      options?.name ? `${key}:${options.name}` : key,
  }),
}));

const agentFields: SkillDocumentField[] = [
  {
    key: 'name',
    labelKey: 'skills.name',
    control: 'text',
    required: true,
    maxLength: 64,
    present: true,
    value: 'example-skill',
  },
  {
    key: 'description',
    labelKey: 'skills.description',
    control: 'textarea',
    required: true,
    maxLength: 1024,
    present: true,
    value: 'Example description',
  },
  {
    key: 'license',
    labelKey: 'skills.license',
    control: 'text',
    required: false,
    maxLength: 1024,
    present: false,
    value: null,
  },
  {
    key: 'compatibility',
    labelKey: 'skills.compatibility',
    control: 'textarea',
    required: false,
    maxLength: 4096,
    present: false,
    value: null,
  },
  {
    key: 'allowed-tools',
    labelKey: 'skills.allowedTools',
    control: 'textarea',
    required: false,
    maxLength: 4096,
    present: true,
    value: 'Read, Grep',
  },
];

const fullFields: SkillDocumentField[] = [
  ...agentFields,
  {
    key: 'user-invocable',
    labelKey: 'skills.userInvocable',
    control: 'boolean',
    required: false,
    present: true,
    value: true,
  },
  {
    key: 'paths',
    labelKey: 'skills.paths',
    control: 'string-list',
    required: false,
    maxLength: 1024,
    present: true,
    value: ['src/**', 'docs/**'],
  },
];

function createDocument(fields: SkillDocumentField[] = fullFields): SkillDocumentResult {
  return {
    success: true,
    editable: true,
    revision: 'revision-1',
    body: '# Example\n',
    fields,
  };
}

function renderDialog(
  document: SkillDocumentResult | null = createDocument(),
  overrides: Partial<React.ComponentProps<typeof SkillEditorDialog>> = {},
) {
  const props: React.ComponentProps<typeof SkillEditorDialog> = {
    isOpen: true,
    skillName: 'example-skill',
    loading: false,
    saving: false,
    document,
    onClose: vi.fn(),
    onReload: vi.fn(),
    onOpenInIde: vi.fn(),
    onSave: vi.fn(),
    ...overrides,
  };
  render(<SkillEditorDialog {...props} />);
  return props;
}

describe('SkillEditorDialog', () => {
  it('renders only the fields declared by the backend schema', () => {
    const { rerender } = render(
      <SkillEditorDialog
        isOpen
        skillName="example-skill"
        loading={false}
        saving={false}
        document={createDocument(agentFields)}
        onClose={vi.fn()}
        onReload={vi.fn()}
        onOpenInIde={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    expect(document.querySelectorAll('.skill-editor-fields > .form-group')).toHaveLength(5);
    expect(screen.queryByLabelText('skills.userInvocable')).toBeNull();
    expect(screen.queryByLabelText('skills.paths')).toBeNull();

    rerender(
      <SkillEditorDialog
        isOpen
        skillName="example-skill"
        loading={false}
        saving={false}
        document={createDocument(fullFields)}
        onClose={vi.fn()}
        onReload={vi.fn()}
        onOpenInIde={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    expect(document.querySelectorAll('.skill-editor-fields > .form-group')).toHaveLength(7);
    expect(screen.getByLabelText('skills.userInvocable')).toBeTruthy();
    expect(screen.getByLabelText('skills.paths')).toBeTruthy();
  });

  it('submits only changed frontmatter fields together with the markdown body', () => {
    const onSave = vi.fn();
    renderDialog(createDocument(), { onSave });

    fireEvent.change(screen.getByLabelText('skills.description'), {
      target: { value: 'Updated description' },
    });
    fireEvent.change(screen.getByLabelText('skills.editor.markdownBody'), {
      target: { value: '# Updated\n' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));

    expect(onSave).toHaveBeenCalledWith({
      changes: { description: 'Updated description' },
      body: '# Updated\n',
    });
  });

  it('converts string-list controls into line-based arrays', () => {
    const onSave = vi.fn();
    renderDialog(createDocument(), { onSave });

    fireEvent.change(screen.getByLabelText('skills.paths'), {
      target: { value: 'src/**\ntest/**' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));

    expect(onSave).toHaveBeenCalledWith({
      changes: { paths: ['src/**', 'test/**'] },
      body: '# Example\n',
    });
  });

  it('disables saving on conflict while keeping reload available', () => {
    const onReload = vi.fn();
    const onSave = vi.fn();
    renderDialog(
      {
        ...createDocument(),
        conflict: true,
        error: 'SKILL.md changed outside Codemoss; reload before saving',
      },
      { onReload, onSave },
    );

    expect(screen.getByText('skills.editor.conflictTitle')).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'common.save' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'skills.editor.reload' }));
    expect(onReload).toHaveBeenCalledOnce();
    expect(onSave).not.toHaveBeenCalled();
  });

  it('does not render an editable form after a parse failure', () => {
    renderDialog({
      success: false,
      editable: false,
      parseError: true,
      conflict: false,
      error: 'Invalid YAML frontmatter',
    });

    expect(screen.getByText('Invalid YAML frontmatter')).toBeTruthy();
    expect(screen.queryByLabelText('skills.name')).toBeNull();
    expect(
      (screen.getByRole('button', { name: 'common.save' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('keeps the IDE open action available independently of editability', () => {
    const onOpenInIde = vi.fn();
    renderDialog(
      {
        success: false,
        editable: false,
        error: 'Read failed',
      },
      { onOpenInIde },
    );

    fireEvent.click(screen.getByRole('button', { name: 'skills.editor.openInIde' }));
    expect(onOpenInIde).toHaveBeenCalledOnce();
  });
});
