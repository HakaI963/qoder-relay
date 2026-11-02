# Verified Qoder wire protocol (as used by qoder-relay)

All facts below were confirmed against the live gateway (`api3.qoder.sh`) and by reading the
qodercli 1.1.79 binary. The reference implementation lives in `tools/`.

## 1. Accounts

| Method | Endpoint | Notes |
|---|---|---|
| Browser login | `GET https://qoder.com/device/selectAccounts?challenge=…&challenge_method=S256&nonce=…&machine_id=…&client_id=e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb` | PKCE S256; approve in browser |
| Poll | `GET https://center.qoder.sh/api/v1/deviceToken/poll?nonce=…&verifier=…&challenge_method=S256` | 404 = pending, 200 = `{token, refresh_token, refresh_token_expires_at, …, token_type:"Bearer"}` |
| Refresh | `POST https://center.qoder.sh/api/v1/deviceToken/refresh` `{"refresh_token":"drt-…","machine_id":"…"}` | returns `device_token` (`dt-…`, ~30 days) + rotated `drt-…` |
| PAT exchange | `POST https://openapi.qoder.sh/api/v1/jobToken/exchange` `{"personal_token":"pt-…"}` | PAT from qoder.com/account/integrations |
| User info | `GET https://center.qoder.sh/api/v1/userinfo` `Authorization: Bearer dt-…` | plain bearer works, returns `{id}` = uid |
| Plan | `GET https://center.qoder.sh/api/v2/user/plan` | plain bearer works |

## 2. Request signing (COSY)

Every API request from the CLI is signed by a wasm module (`qoder_auth_wasm_bg.wasm`,
extracted verbatim into `tools/cosy/`). The module exports:

- `generate_runtime_auth_fields({"accessToken","refreshToken","uid","machineId"})` →
  `{"encrypt_user_info","key"}` — per-account runtime auth blob.
- `new QoderContext(machineId, cosyVersion, JSON.stringify({uid, encrypt_user_info, key}))`
- `ctx.prepareInferRequest(endpoint, plaintextBodyJson, modelKey, modelSource)` →
  `{ url, headers{Authorization: Bearer COSY.…, Cosy-Key, Cosy-Date, Cosy-MachineId, …}, body }`
  (body is encrypted; `Encode=1` in the URL).
- `ctx.prepareRequest(endpoint, path, method, "auth", body?)` → same for arbitrary calls (e.g. model list).

Plain `Authorization: Bearer dt-…` is rejected by the gateway with `403 {"code":"101","message":"Signature invalid"}`;
the signed variants return `200`. The relay therefore ships the wasm and runs it in a hidden
WebView (APK) / Node (tools).

## 3. Inference

```
POST https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation
     ?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1
Authorization: Bearer COSY.<sig>
Accept: text/event-stream
```

Body (the “SL” schema; `tools/wire.mjs` builds it): `request_id`, `request_set_id`, `chat_record_id`,
`session_id`, `stream:true`, `chat_task:"FREE_INPUT"`, `chat_context{text,extra}`, `agent_id:"agent_common"`,
`task_id:"common"`, `session_type:"cli"`, `model_config{key,display_name,…}`, `system`,
`messages[{role,content:[{type:"text",text}]}]`, `tools`, `parameters{max_tokens,reasoning_effort,enable_thinking}`.

Response: SSE lines `data:{ "statusCodeValue":200, "body":"<openai-chunk-json>" }` where the inner body is a
standard OpenAI chunk (`delta.content`, `delta.reasoning_content`, `delta.tool_calls`, `finish_reason`),
then a usage-only chunk (`usage.prompt_tokens/completion_tokens/total_tokens/…`), then `body:"[DONE]"`.
Non-chunk envelopes (e.g. `{"firstTokenDuration":…}`) must be skipped.

## 4. Model catalog

```
GET https://api3.qoder.sh/algo/api/v2/model/list   (signed GET)
→ {"chat":[{key,display_name,is_reasoning,is_default,enable,…}], …}
```

Default plan: `qfmodel` = Qwen3.8-Flash, `dfmodel` = DeepSeek-Flash (both enabled, reasoning).

## 5. Client-side notes

- The CLI compresses request bodies with zstd in its own transport; the gateway accepts plain JSON
  when sent with a valid COSY signature (verified — the relay sends plaintext).
- Tokens are 30-day `dt-…` with rotating `drt-…` refresh tokens; regenerate COSY fields after a refresh.
