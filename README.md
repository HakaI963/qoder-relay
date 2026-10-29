# qoder-relay

OpenAI-compatible relay for your Qoder accounts, running as an on-device Android server.

## What it does

- **Add Qoder accounts** — either sign in through the browser (same device-login flow the Qoder CLI uses, no password handling) or paste a Personal Access Token (`pt-…`, created at qoder.com → Account → Integrations).
- **One OpenAI-compatible API** — `POST /v1/chat/completions` (stream + non-stream) and `GET /v1/models` on your phone's LAN address.
- **Auto routing** — true round-robin over enabled accounts. A stalled account is failed over after
  `firstTokenMs` (default 45 s, configurable live from the dashboard) instead of sitting on a socket
  until the client gives up.
- **Token accounting** — total and per-account tokens in/out, request counts, failovers, per-request
  time-to-first-byte and a rolling request log.
- **Model policy** — universal pool (any account can serve a model) or per-account allow-lists.
- **Dashboard** — one responsive web console (`/ui`), rendered inside the app and reachable from any
  browser on the LAN: overview with live pool/latency, account manager, model allow-lists, usage log,
  a streaming test console that shows relay failover notes, and settings for port/key/timeouts.

## Why response headers never block

The old build ran Qoder upstream *inside* `serve()`, so a client saw no HTTP headers until the
upstream answered — and upstream (or a 401 refresh, or a wedged WebView signature) sometimes takes
minutes. Clients then died with `Timed out waiting for response headers`.

Now `/v1/chat/completions` returns `200 + text/event-stream` immediately from a worker-fed pipe
(`Stream.kt`) and pumps a `: keep-alive` comment every `heartbeatMs` while the pool is being walked.
Chunked output is flushed per SSE block, so nothing sits in a socket buffer. If every account
fails, the client gets an in-band `relay_error` chunk plus `[DONE]` rather than a hang. OkHttp no
longer uses a 360 s `callTimeout` for inference; it uses a per-call **header** timeout that is cleared
as soon as headers arrive, so long streams are fine but dead accounts fail over fast.

## Protocol

The full wire protocol (device login, COSY wasm signing, inference SSE, model list) is documented in
[`tools/PROTOCOL.md`](tools/PROTOCOL.md). A standalone, tested reference client lives in `tools/`:

```bash
cp tools/accounts.example.json tools/accounts.json   # fill dt-/drt-, uid, machine_id
node tools/relay.mjs --port 8791                     # OpenAI-compatible test relay
curl localhost:8791/v1/chat/completions -H "Authorization: Bearer sk-relay-test" \
  -d '{"model":"qfmodel","messages":[{"role":"user","content":"hi"}]}'
```

## Build

The APK is compiled in GitHub Actions (`.github/workflows/build-apk.yml`) — no local Android SDK needed.
Artifact: `qoder-relay-debug-apk` from any workflow run.

```
gh run --repo <you>/qoder-relay list
gh run download --repo <you>/qoder-relay --name qoder-relay-debug-apk
```

## Use

```bash
curl http://<device-ip>:8788/v1/chat/completions \
  -H "Authorization: Bearer <relay-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"qfmodel","messages":[{"role":"user","content":"ping"}],"stream":true}'
```

OpenAI SDK: `base_url="http://<device-ip>:8788/v1"`, `api_key="<relay-key>"`.
Pin an account: add `"relay_account":"<account-id>"` to the request body.

The relay-key and LAN URL are shown on the dashboard **Overview** (tap *show* next to the key).
OpenAI SDK: `base_url="http://<device-ip>:8788/v1"`, `api_key="<relay-key>"`.
Pin an account: add `"relay_account":"<account-id>"` to the request body (ids are listed on the
Accounts tab and in `GET /relay/accounts`).

## Dashboard / admin API

The console is a single asset (`app/src/main/assets/dashboard.html`) served at `/`, `/ui`.
It is the same page in the app (WebView, key handed over by a `JavascriptInterface`) and in a browser
(`http://<ip>:8788/ui?key=…`). On loopback, `GET /relay/boot` returns the key so the app never needs typing.

| Route | Purpose |
|---|---|
| `GET /relay/status` | counts, uptime, in-flight, pool state |
| `GET /relay/usage` | per-account totals + rolling request log (with `ttfb`) |
| `GET /relay/catalog` | model → display name, reasoning, serving accounts |
| `GET,POST /relay/config` | read/update port, key, `firstTokenMs`, `heartbeatMs`, `cooldownMs`, `overallMs`, `maxAttempts`, start/stop |
| `POST /relay/selftest` | times token → COSY fields → model list for one account |
| `GET,POST /relay/models/refresh` | one account or the whole pool |
| `POST /relay/device-start`, `GET /relay/device-poll` | browser sign-in |
| `POST/PUT/DELETE /relay/accounts[/{id}]` | PAT add, patch (label/enabled/universal/models/clearCooldown), remove |

