// Wire-shaped body builder + OpenAI chunk unwrapper. This is the exact schema the CLI sends
// (verified live against api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation).
export function buildWire({ modelKey, modelConfig, openai, requestId, sessionId, requestSetId }) {
  const messages = Array.isArray(openai.messages) ? openai.messages : [];
  const systemParts = messages.filter(m => m.role === 'system')
    .map(m => typeof m.content === 'string' ? m.content : (m.content || []).map(c => c.text || '').join('\n'))
    .filter(Boolean);
  const conv = [];
  for (const m of messages) {
    if (m.role === 'system') continue;
    if (m.role === 'tool') { conv.push({ role: 'tool', tool_call_id: m.tool_call_id, content: m.content }); continue; }
    let content;
    if (typeof m.content === 'string') content = [{ type: 'text', text: m.content }];
    else if (Array.isArray(m.content)) content = m.content.map(c => c.type === 'text' ? { type: 'text', text: c.text } : c);
    else content = [];
    const item = { role: m.role, content };
    if (m.tool_calls) item.tool_calls = m.tool_calls;
    if (m.reasoning_content) item.reasoning_content = m.reasoning_content;
    conv.push(item);
  }
  const lastUser = [...messages].reverse().find(m => m.role === 'user');
  const lastText = !lastUser ? '' : (typeof lastUser.content === 'string' ? lastUser.content
    : (lastUser.content || []).map(c => c.text || '').join('\n'));

  const params = { max_tokens: openai.max_tokens || openai.max_completion_tokens || 32000 };
  const effort = openai.reasoning_effort;
  if (effort) { params.reasoning_effort = effort; params.enable_thinking = true; }

  return {
    defaultReasoningEffort: effort || 'high',
    request_id: requestId,
    request_set_id: requestSetId || requestId,
    chat_record_id: requestId,
    session_id: sessionId,
    stream: true,
    chat_task: 'FREE_INPUT',
    chat_context: {
      text: lastText,
      features: [],
      extra: { context: [], modelConfig: { key: modelKey, is_reasoning: !!(modelConfig && modelConfig.is_reasoning) }, originalContent: lastText },
      chatPrompt: '',
      imageUrls: null,
    },
    is_reply: true, is_retry: false, source: 1, version: '3', agent_id: 'agent_common',
    task_id: 'common', session_type: 'cli', aliyun_user_type: '',
    model_config: modelConfig,
    custom_model: null,
    system: systemParts.join('\n\n'),
    messages: conv,
    tools: Array.isArray(openai.tools) ? openai.tools : [],
    parameters: params,
  };
}

export function modelConfigFor(key, displayName, isReasoning) {
  return {
    key, display_name: displayName || key, model: '', format: 'openai',
    is_vl: true, is_reasoning: !!isReasoning, api_key: '', url: '',
    source: 'system', max_input_tokens: 180000,
  };
}

/** SSE envelope {statusCodeValue, body} -> inner OpenAI chunk object or null. */
export function unwrapEnvelope(data) {
  try {
    const o = JSON.parse(data);
    if (o && o.statusCodeValue !== undefined && typeof o.body === 'string') {
      if (o.body.trim() === '[DONE]') return null;
      return JSON.parse(o.body);
    }
    return o;
  } catch { return null; }
}
