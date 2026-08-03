import React, { Component } from 'react';
import type { ReactNode } from 'react';
import i18n from '../i18n/config';
import { CheckIcon, CopyIcon, RefreshIcon, XCircleIcon } from './Icons';
import './ErrorBoundary.css';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error?: Error;
  errorInfo?: React.ErrorInfo;
  copied: boolean;
}

/**
 * Collect system environment information for debugging
 */
function getSystemInfo(): string {
  const info: string[] = [];

  info.push(`Time: ${new Date().toISOString()}`);
  info.push(`UserAgent: ${navigator.userAgent}`);
  info.push(`Platform: ${navigator.platform}`);
  info.push(`Language: ${navigator.language}`);
  info.push(`URL: ${window.location.href}`);
  info.push(`Screen: ${window.screen.width}x${window.screen.height}`);
  info.push(`Viewport: ${window.innerWidth}x${window.innerHeight}`);
  info.push(`DevicePixelRatio: ${window.devicePixelRatio}`);

  // Check if sendToJava bridge exists (JCEF environment)
  info.push(`JCEF Bridge: ${typeof window.sendToJava === 'function' ? 'available' : 'not available'}`);

  // Check localStorage availability
  try {
    localStorage.setItem('__test__', '1');
    localStorage.removeItem('__test__');
    info.push(`LocalStorage: available`);
  } catch {
    info.push(`LocalStorage: not available`);
  }

  return info.join('\n');
}

/**
 * Format error details for display and copy
 */
function formatErrorDetails(error?: Error, errorInfo?: React.ErrorInfo): string {
  const parts: string[] = [];

  parts.push('=== Error Information ===');
  if (error) {
    parts.push(`Error: ${error.name}`);
    parts.push(`Message: ${error.message}`);
    if (error.stack) {
      parts.push(`\nStack Trace:\n${error.stack}`);
    }
  }

  if (errorInfo?.componentStack) {
    parts.push(`\nComponent Stack:${errorInfo.componentStack}`);
  }

  parts.push('\n=== System Information ===');
  parts.push(getSystemInfo());

  return parts.join('\n');
}

/**
 * Get translated text with fallback
 */
function t(key: string, fallback: string): string {
  const translated = i18n.t(key);
  // If translation returns the key itself, use fallback
  return translated === key ? fallback : translated;
}

/**
 * Error Boundary Component
 * Catches JavaScript errors anywhere in the child component tree
 * and displays a fallback UI instead of crashing the whole app
 */
class ErrorBoundary extends Component<Props, State> {
  private copyTimerId: ReturnType<typeof setTimeout> | null = null;

  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, copied: false };
  }

  componentWillUnmount() {
    if (this.copyTimerId) {
      clearTimeout(this.copyTimerId);
    }
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    // Update state so the next render will show the fallback UI
    return { hasError: true, error, copied: false };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    // Log error details for debugging
    console.error('[ErrorBoundary] Caught error:', error);
    console.error('[ErrorBoundary] Error info:', errorInfo);
    console.error('[ErrorBoundary] Component stack:', errorInfo.componentStack);

    this.setState({
      error,
      errorInfo,
    });
  }

  handleReset = () => {
    this.setState({ hasError: false, error: undefined, errorInfo: undefined, copied: false });

    // Reload the page to fully reset the app state
    window.location.reload();
  };

  private setCopiedWithAutoReset = () => {
    if (this.copyTimerId) {
      clearTimeout(this.copyTimerId);
    }
    this.setState({ copied: true });
    this.copyTimerId = setTimeout(() => {
      this.setState({ copied: false });
      this.copyTimerId = null;
    }, 2000);
  };

  handleCopyError = async () => {
    const errorDetails = formatErrorDetails(this.state.error, this.state.errorInfo);

    try {
      await navigator.clipboard.writeText(errorDetails);
      this.setCopiedWithAutoReset();
    } catch {
      // Fallback: create a textarea and copy from there
      const textarea = document.createElement('textarea');
      textarea.value = errorDetails;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        this.setCopiedWithAutoReset();
      } catch {
        console.error('Failed to copy error details');
      }
      document.body.removeChild(textarea);
    }
  };

  render() {
    if (this.state.hasError) {
      // Custom fallback UI if provided
      if (this.props.fallback) {
        return this.props.fallback;
      }

      const errorDetails = formatErrorDetails(this.state.error, this.state.errorInfo);

      // Default fallback UI — role/aria-live announce the error to assistive
      // tech, otherwise screen-reader users have no signal that the surface
      // suddenly switched to an error state.
      return (
        <div
          className="error-boundary"
          role="alert"
          aria-live="assertive"
          aria-atomic="true"
        >
          <div className="error-boundary-card">
            {/* Header */}
            <div className="error-boundary-header">
              <XCircleIcon
                size={16}
                className="error-boundary-icon"
                aria-hidden="true"
              />
              <h2 className="error-boundary-title">
                {t('errorBoundary.title', 'Something went wrong')}
              </h2>
            </div>

            {/* Description */}
            <p className="error-boundary-description">
              {t('errorBoundary.description', 'The application encountered an unexpected error. Please copy the error details below and share them with the developer for troubleshooting.')}
            </p>

            {/* Possible causes hint */}
            <div className="error-boundary-causes-box">
              <div className="error-boundary-causes-title">
                {t('errorBoundary.possibleCauses', 'Possible causes:')}
              </div>
              <ul className="error-boundary-causes-list">
                <li>{t('errorBoundary.cause1', 'Node.js is not installed or not configured correctly')}</li>
                <li>{t('errorBoundary.cause2', 'Network connection issues')}</li>
                <li>{t('errorBoundary.cause3', 'Plugin or IDE compatibility issues')}</li>
                <li>{t('errorBoundary.cause4', 'Insufficient system resources')}</li>
              </ul>
            </div>

            {/* Error Details */}
            {this.state.error && (
              <details className="error-boundary-details" open>
                <summary className="error-boundary-summary">
                  {t('errorBoundary.errorDetails', 'Error Details')}
                </summary>
                <pre className="error-boundary-pre">
                  {errorDetails}
                </pre>
              </details>
            )}

            {/* Action Buttons */}
            <div className="error-boundary-actions">
              {/* Copy Error Button */}
              <button
                onClick={this.handleCopyError}
                aria-label={t('errorBoundary.copyError', 'Copy Error Info')}
                className={`error-boundary-button ${this.state.copied ? '' : 'error-boundary-button-primary'}`}
              >
                {this.state.copied
                  ? <CheckIcon size={16} className="error-boundary-button-icon" />
                  : <CopyIcon size={16} className="error-boundary-button-icon" />}
                {this.state.copied
                  ? t('errorBoundary.copied', 'Copied!')
                  : t('errorBoundary.copyError', 'Copy Error Info')}
              </button>

              {/* Reload Button */}
              <button
                onClick={this.handleReset}
                aria-label={t('errorBoundary.reload', 'Reload Application')}
                className="error-boundary-button"
              >
                <RefreshIcon size={16} className="error-boundary-button-icon" />
                {t('errorBoundary.reload', 'Reload Application')}
              </button>
            </div>

            {/* Help text */}
            <p className="error-boundary-help-text">
              {t('errorBoundary.helpText', 'If the problem persists, please copy the error information and submit it as an issue on GitHub, or share it in the community group.')}
            </p>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
