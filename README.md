# qoder-relay

OpenAI-compatible relay for your Qoder accounts, running as an on-device Android server.

## What it does

- **Add Qoder accounts** — either sign in through the browser (same device-login flow the Qoder CLI uses, no password handling) or paste a Personal Access Token (`pt-…`, created at qoder.com → Account → Integrations).
- **One OpenAI-compatible API** — `POST /v1/chat/completions` (stream + non-stream) and `GET /v1/models` on your phone's LAN address.
- **Auto routing** — requests are spread over enabled accounts; on 401 the token is refreshed, on error/timeout the next account takes over automatically (30 s cooldown).
- **Token accounting** — total and per-account tokens in/out, request counts, failovers, recent-request log.
- **Model policy** — universal pool (any account can serve a model) or per-account allow-lists.
- **Dashboard** — WebView admin UI with 3 themes (graphite / warm-neutral / midnight-slate).

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

The relay-key and LAN URL are shown in the app under **Connect**.
