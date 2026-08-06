import { useTranslation } from 'react-i18next';
import { BookIcon, ExternalLinkIcon, InfoIcon, RocketIcon } from '../Icons';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../shared/BaseDialog';
import { ClickSpark } from '../react-bits';

interface McpHelpDialogProps {
  onClose: () => void;
}

export function McpHelpDialog({ onClose }: McpHelpDialogProps) {
  const { t } = useTranslation();

  return (
    <BaseDialog isOpen onClose={onClose} animation="pop">
      <DialogHeader title={t('mcp.help.title')} onClose={onClose} />
      <DialogBody>
        <div className="help-content">
          <section className="help-section">
            <h4>
              <InfoIcon size={16} />
              {t('mcp.help.protocol.title')}
            </h4>
            <p>
              {t('mcp.help.protocol.description')}
            </p>
          </section>

          <section className="help-section">
            <h4>
              <RocketIcon size={16} />
              {t('mcp.help.features.title')}
            </h4>
            <ul>
              <li><strong>{t('mcp.help.features.toolExtension.label')}</strong>：{t('mcp.help.features.toolExtension.description')}</li>
              <li><strong>{t('mcp.help.features.dataConnection.label')}</strong>：{t('mcp.help.features.dataConnection.description')}</li>
              <li><strong>{t('mcp.help.features.security.label')}</strong>：{t('mcp.help.features.security.description')}</li>
              <li><strong>{t('mcp.help.features.integration.label')}</strong>：{t('mcp.help.features.integration.description')}</li>
            </ul>
          </section>

          <section className="help-section">
            <h4>
              <BookIcon size={16} />
              {t('mcp.help.configuration.title')}
            </h4>
            <p>{t('mcp.help.configuration.description')}</p>
            <ul>
              <li>
                <strong>STDIO</strong>：{t('mcp.help.configuration.stdio.description')}
                <code className="inline-code">{t('mcp.help.configuration.stdio.example')}</code>
              </li>
              <li>
                <strong>HTTP/SSE</strong>：{t('mcp.help.configuration.httpSse.description')}
                <code className="inline-code">{t('mcp.help.configuration.httpSse.example')}</code>
              </li>
            </ul>
          </section>

          <section className="help-section">
            <h4>
              <ExternalLinkIcon size={16} />
              {t('mcp.help.learnMore.title')}
            </h4>
            <p>
              {t('mcp.help.learnMore.description')}
              <a
                href="https://modelcontextprotocol.io"
                target="_blank"
                rel="noopener noreferrer"
                className="help-link"
              >
                modelcontextprotocol.io
                <ExternalLinkIcon size={16} />
              </a>
            </p>
          </section>
        </div>
      </DialogBody>
      <DialogFooter>
        <ClickSpark>
          <button className="btn btn-primary" onClick={onClose}>{t('mcp.help.gotIt')}</button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
