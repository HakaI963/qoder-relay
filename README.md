# Qoder Relay

Runs an OpenAI-compatible API on your Android phone, backed by your Qoder accounts.

## Use

1. Open the app → **+ Add** → sign in with your browser, or paste a personal access token (`pt-…`).
2. Copy the **Base URL** and **API key** from the home screen into your client:

```bash
curl http://<phone-ip>:8788/v1/chat/completions \
  -H "Authorization: Bearer <api-key>" -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"hi"}],"stream":true}'
```

OpenAI SDK: `base_url="http://<phone-ip>:8788/v1"`, `api_key="<api-key>"`.

Routes: `GET /v1/models`, `POST /v1/chat/completions` (stream and non-stream), `GET /health`.
Unknown model names map to `auto`. To force one account, add `"relay_account":"<name>"` to the request body.

## Keeping it running

The relay runs as a foreground service. It holds a CPU wake lock and a Wi-Fi lock, restarts itself
after boot, after an app update or after being killed, and keeps running when you swipe the app away.
For the best reliability, tap **Allow** on the battery card. On Xiaomi, Oppo, Vivo and Samsung phones,
also turn on Autostart and set the app's battery mode to "No restrictions". The notification has a **Stop** button.

## Build

GitHub Actions (`.github/workflows/build-apk.yml`) builds `app-release.apk` on every push.
Local build: `gradle assembleRelease` (JDK 17, Android SDK 35). Unit tests: `gradle testReleaseUnitTest`.

The wire protocol is documented in [PROTOCOL.md](PROTOCOL.md).
