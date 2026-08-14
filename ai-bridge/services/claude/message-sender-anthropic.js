// @ts-check
/**
 * Anthropic SDK direct message sender.
 * NOTE: SDK has been removed. This module is no longer functional in CLI-only mode.
 * Fallback for third-party API proxies that don't support the Claude Agent SDK.
 */

import { randomUUID } from 'crypto';
import { loadClaudeSettings, getCliUserAgent } from '../../config/api-config.js';
import { selectWorkingDirectory } from '../../utils/path-utils.js';
import { resolveModelFromSettings } from '../../utils/model-utils.js';
import { loadSessionHistory, persistJsonlMessage } from './session-service.js';
// NOTE: SDK imports removed — Anthropic/Bedrock SDK no longer available in CLI-only mode.
import { truncateErrorContent } from './message-utils.js';
import { buildContentBlocks } from './attachment-service.js';

/**
 * 通过 Anthropic SDK 直接发送消息(第三方 API 代理不支持 Claude Agent SDK 时的回退路径)。
 * NOTE: SDK has been removed. This function always throws in CLI-only mode.
 * @param {string} message         消息文本
 * @param {string} resumeSessionId 续接的会话 ID
 * @param {string} cwd             工作目录
 * @param {string} permissionMode  权限模式
 * @param {string} model           模型名
 * @param {string} apiKey          API Key / Auth Token
 * @param {string} baseUrl         自定义 Base URL
 * @param {string} authType        鉴权类型('auth_token' | 'aws_bedrock' | 其它)
 * @param {any[]} [attachments=[]] 附件列表(外部 stdin 数据,松散类型)
 * @returns {Promise<void>}
 */
export async function sendMessageWithAnthropicSDK(_message, _resumeSessionId, _cwd, _permissionMode, _model, _apiKey, _baseUrl, _authType, _attachments = []) {
  // SDK has been removed — direct Anthropic SDK message sending is not available in CLI-only mode.
  const errorMsg = 'Direct Anthropic SDK is not available in CLI-only mode. Use the standard CLI message sender instead.';
  console.error('[SEND_ERROR]', JSON.stringify({ error: errorMsg }));
  console.log(JSON.stringify({ success: false, error: errorMsg }));
  return;

  try {
    // SDK removed — this code path is no longer reachable
    const anthropicModule = null; // await ensureAnthropicSdk();
    const Anthropic = null;

    const workingDirectory = selectWorkingDirectory(cwd);
    try {
      process.chdir(workingDirectory);
    } catch {
    }

    const sessionId = (resumeSessionId && resumeSessionId !== '') ? resumeSessionId : randomUUID();
    const rawModelId = model || 'claude-sonnet-4-5';

    // FIX: Resolve the actual model name from settings.json model mapping.
    // When using third-party API proxies, the internal model ID (e.g. 'claude-sonnet-4-6')
    // may not be recognized. Use the user's configured model mapping if available.
    const sdkSettings = loadClaudeSettings();
    const modelId = resolveModelFromSettings(rawModelId, sdkSettings?.env);
    console.log('[DEBUG] (AnthropicSDK) Model resolved for API:', rawModelId, '->', modelId);

    // Build CLI-style headers for API identification
    const cliHeaders = {
      'x-app': 'cli',
      'User-Agent': getCliUserAgent()
    };

    // Use the correct SDK parameters based on auth type
    // authType = 'auth_token': use authToken parameter (Bearer authentication)
    // authType = 'api_key': use apiKey parameter (x-api-key authentication)
    let client;
    if (authType === 'auth_token') {
      console.log('[DEBUG] Using Bearer authentication (ANTHROPIC_AUTH_TOKEN)');
      // Use authToken parameter (Bearer authentication) and clear apiKey
      client = new Anthropic({
        authToken: apiKey,
        apiKey: null,  // Explicitly set to null to avoid sending the x-api-key header
        baseURL: baseUrl || undefined,
        defaultHeaders: cliHeaders
      });
      // Prefer Bearer (ANTHROPIC_AUTH_TOKEN) and prevent sending x-api-key
      delete process.env.ANTHROPIC_API_KEY;
      process.env.ANTHROPIC_AUTH_TOKEN = apiKey;
    } else if (authType === 'aws_bedrock') {
      console.log('[DEBUG] Using AWS_BEDROCK authentication (AWS_BEDROCK)');
      // Dynamically load Bedrock SDK
      const bedrockModule = await ensureBedrockSdk();
      const AnthropicBedrock = bedrockModule.AnthropicBedrock || bedrockModule.default || bedrockModule;
      client = new AnthropicBedrock({
        defaultHeaders: cliHeaders
      });
    } else {
      console.log('[DEBUG] Using API Key authentication (ANTHROPIC_API_KEY)');
      // Use apiKey parameter (x-api-key authentication)
      client = new Anthropic({
        apiKey,
        baseURL: baseUrl || undefined,
        defaultHeaders: cliHeaders
      });
    }

    console.log('[MESSAGE_START]');
    console.log('[SESSION_ID]', sessionId);
    console.log('[DEBUG] Using Anthropic SDK fallback for custom Base URL (non-streaming)');
    console.log('[DEBUG] Model:', modelId);
    console.log('[DEBUG] Base URL:', baseUrl);
    console.log('[DEBUG] Auth type:', authType || 'api_key (default)');

    const userContent = (Array.isArray(attachments) && attachments.length > 0)
      ? await buildContentBlocks(attachments, message, modelId)
      : [{ type: 'text', text: message }];

    persistJsonlMessage(sessionId, cwd, {
      type: 'user',
      message: { content: userContent }
    });

    /** @type {Array<{ role: string; content: unknown }>} */
    let messagesForApi = [{ role: 'user', content: userContent }];
    if (resumeSessionId && resumeSessionId !== '') {
      const historyMessages = loadSessionHistory(sessionId, cwd);
      if (historyMessages.length > 0) {
        messagesForApi = [...historyMessages, { role: 'user', content: userContent }];
        console.log('[DEBUG] Loaded', historyMessages.length, 'history messages for session continuity');
      }
    }

    const systemMsg = {
      type: 'system',
      subtype: 'init',
      cwd: workingDirectory,
      session_id: sessionId,
      tools: [],
      mcp_servers: [],
      model: modelId,
      permissionMode: permissionMode || 'default',
      apiKeySource: 'ANTHROPIC_API_KEY',
      uuid: randomUUID()
    };
    console.log('[MESSAGE]', JSON.stringify(systemMsg));

    console.log('[DEBUG] Calling messages.create() with non-streaming API...');

    const response = await client.messages.create({
      model: modelId,
      max_tokens: 8192,
      messages: messagesForApi
    });

    console.log('[DEBUG] API response received');

    if (response.error || response.type === 'error') {
      const errorMsg = response.error?.message || response.message || 'Unknown API error';
      console.error('[API_ERROR]', errorMsg);

      const errorContent = [{
        type: 'text',
        text: `API error: ${errorMsg}

Possible causes:
1. API Key is not configured correctly
2. Third-party proxy service configuration issue
3. Please check the configuration in ~/.claude/settings.json`
      }];

      const assistantMsg = {
        type: 'assistant',
        message: {
          id: randomUUID(),
          model: modelId,
          role: 'assistant',
          stop_reason: 'error',
          type: 'message',
          usage: {
            input_tokens: 0,
            output_tokens: 0,
            cache_creation_input_tokens: 0,
            cache_read_input_tokens: 0
          },
          content: errorContent
        },
        session_id: sessionId,
        uuid: randomUUID()
      };
      console.log('[MESSAGE]', JSON.stringify(assistantMsg));
      console.log('[CONTENT]', truncateErrorContent(errorContent[0].text));

      const resultMsg = {
        type: 'result',
        subtype: 'error',
        is_error: true,
        duration_ms: 0,
        num_turns: 1,
        result: errorContent[0].text,
        session_id: sessionId,
        total_cost_usd: 0,
        usage: { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        uuid: randomUUID()
      };
      console.log('[MESSAGE]', JSON.stringify(resultMsg));
      console.log('[MESSAGE_END]');
      console.log(JSON.stringify({ success: false, error: errorMsg }));
      return;
    }

    const respContent = response.content || [];
    const usage = response.usage || {};

    const assistantMsg = {
      type: 'assistant',
      message: {
        id: response.id || randomUUID(),
        model: response.model || modelId,
        role: 'assistant',
        stop_reason: response.stop_reason || 'end_turn',
        type: 'message',
        usage: {
          input_tokens: usage.input_tokens || 0,
          output_tokens: usage.output_tokens || 0,
          cache_creation_input_tokens: 0,
          cache_read_input_tokens: 0
        },
        content: respContent
      },
      session_id: sessionId,
      uuid: randomUUID()
    };
    console.log('[MESSAGE]', JSON.stringify(assistantMsg));

    persistJsonlMessage(sessionId, cwd, {
      type: 'assistant',
      message: { content: respContent }
    });

    for (const block of respContent) {
      if (block.type === 'text') {
        console.log('[CONTENT]', truncateErrorContent(block.text));
      }
    }

    const resultMsg = {
      type: 'result',
      subtype: 'success',
      is_error: false,
      duration_ms: 0,
      num_turns: 1,
      result: respContent.map((/** @type {any} */ b) => b.type === 'text' ? b.text : '').join(''),
      session_id: sessionId,
      total_cost_usd: 0,
      usage: {
        input_tokens: usage.input_tokens || 0,
        output_tokens: usage.output_tokens || 0,
        cache_creation_input_tokens: 0,
        cache_read_input_tokens: 0
      },
      uuid: randomUUID()
    };
    console.log('[MESSAGE]', JSON.stringify(resultMsg));

    console.log('[MESSAGE_END]');
    console.log(JSON.stringify({ success: true, sessionId }));

  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    console.error('[SEND_ERROR]', errMsg);
    const resp = /** @type {any} */ (error)?.response;
    if (resp) {
      console.error('[ERROR_DETAILS] Status:', resp.status);
      console.error('[ERROR_DETAILS] Data:', JSON.stringify(resp.data));
    }
  }
}
