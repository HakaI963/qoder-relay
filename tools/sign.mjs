// COSY request signer — wraps the original qoder_auth wasm module.
// Proven against the live gateway: produces the exact Authorization/Cosy-* headers and the encrypted body.
import { createRequire } from 'module';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const require = createRequire(import.meta.url);
const dir = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(dir, 'cosy', 'auth_impl.mjs'));
await mod.default({ module_or_path: fs.readFileSync(path.join(dir, 'cosy', 'auth.wasm')) });

const VERSION = '1.1.79';

export function authFields({ accessToken, refreshToken, uid, machineId }) {
  const out = mod.generate_runtime_auth_fields(JSON.stringify({
    accessToken, refreshToken, uid, machineId,
  }));
  return JSON.parse(out); // { encrypt_user_info, key }
}

export class Signer {
  constructor({ machineId, uid, encryptUserInfo, key }) {
    this.ctx = new mod.QoderContext(
      machineId || '',
      VERSION,
      JSON.stringify({ uid: uid || '', encrypt_user_info: encryptUserInfo || '', key: key || '' })
    );
  }

  /** Signed inference request: endpoint base + plaintext body JSON -> { url, headers, body } */
  prepareInfer(endpoint, bodyJson, modelKey, modelSource) {
    const r = this.ctx.prepareInferRequest(endpoint, bodyJson, modelKey, modelSource);
    const headers = {};
    r.headers.forEach((v, k) => (headers[k] = v));
    return { url: r.url, headers, body: r.body };
  }

  /** Signed arbitrary request (e.g. GET model list). */
  prepareRequest(endpoint, apiPath, method, body) {
    const r = this.ctx.prepareRequest(endpoint, apiPath, method, 'auth', body, undefined);
    const headers = {};
    r.headers.forEach((v, k) => (headers[k] = v));
    return { url: r.url, headers, body: r.body || '' };
  }

  free() { try { this.ctx.free(); } catch {} }
}

export { VERSION };
