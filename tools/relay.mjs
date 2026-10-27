// Minimal OpenAI-compatible relay over Qoder accounts — test harness for the protocol.
// Usage: node tools/relay.mjs [--port 8791] [--accounts tools/accounts.json]
import http from 'http';
import https from 'https';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import dns from 'dns';
import { Signer, authFields } from './sign.mjs';
import { buildWire, modelConfigFor, unwrapEnvelope } from './wire.mjs';

const args = process.argv.slice(2);
const arg = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };
const PORT = parseInt(arg('port', '8791'), 10);
const ACC_FILE = arg('accounts', path.join(path.dirname(new URL(import.meta.url).pathname), 'accounts.json'));
const KEY = process.env.RELAY_KEY || 'sk-relay-test';
const PINS = process.env.RELAY_PINS === '1';

const PIN_MAP = {
  'api4.qoder.sh': '8.212.20.107', 'api3.qoder.sh': '47.57.13.70', 'api2.qoder.sh': '47.236.201.84',
  'api1.qoder.sh': '47.236.201.84', 'center.qoder.sh': '8.211.12.32', 'openapi.qoder.sh': '8.211.12.32', 'open.qoder.com': '101.132.70.5',
};

const accounts = JSON.parse(fs.readFileSync(ACC_FILE, 'utf8'));
const saveAccounts = () => fs.writeFileSync(ACC_FILE, JSON.stringify(accounts, null, 1));

function post(url, headers, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const opts = { hostname: u.hostname, port: 443, path: u.pathname + u.search, method: 'POST', headers: { host: u.hostname, ...headers } };
    if (PINS) opts.lookup = (h, o, cb) => PIN_MAP[h] ? (o && o.all ? cb(null, [{ address: PIN_MAP[h], family: 4 }]) : cb(null, PIN_MAP[h], 4)) : dns.lookup(h, o, cb);
    const req = https.request(opts, (res) => { const b = []; res.on('data', c => b.push(c)); res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(b).toString() })); });
    req.on('error', reject); req.write(body); req.end();
  });
}

function get(url, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const opts = { hostname: u.hostname, port: 443, path: u.pathname + u.search, method: 'GET', headers: { host: u.hostname, ...headers } };
    if (PINS) opts.lookup = (h, o, cb) => PIN_MAP[h] ? (o && o.all ? cb(null, [{ address: PIN_MAP[h], family: 4 }]) : cb(null, PIN_MAP[h], 4)) : dns.lookup(h, o, cb);
    const req = https.request(opts, (res) => { const b = []; res.on('data', c => b.push(c)); res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(b).toString() })); });
    req.on('error', reject); req.end();
  });
}

async function refresh(acc) {
  const r = await post('https://center.qoder.sh/api/v1/deviceToken/refresh',
    { 'Content-Type': 'application/json' },
    JSON.stringify({ refresh_token: acc.refresh_token, machine_id: acc.machine_id }));
  if (r.status !== 200) throw new Error('refresh failed: ' + r.status + ' ' + r.body.slice(0, 120));
  const j = JSON.parse(r.body);
  if (j.device_token) acc.access_token = j.device_token;
  if (j.refresh_token) acc.refresh_token = j.refresh_token;
  acc.expires_at = j.expires_at;
  saveAccounts();
  acc._fields = null; acc._signer = null;
}

async function signer(acc) {
  if (!acc.uid) {
    const u = await get('https://center.qoder.sh/api/v1/userinfo', { Authorization: 'Bearer ' + acc.access_token });
    if (u.status === 200) acc.uid = JSON.parse(u.body).id;
  }
  if (!acc._fields) acc._fields = authFields({ accessToken: acc.access_token, refreshToken: acc.refresh_token, uid: acc.uid, machineId: acc.machine_id });
  if (!acc._signer) acc._signer = new Signer({ machineId: acc.machine_id, uid: acc.uid, encryptUserInfo: acc._fields.encrypt_user_info, key: acc._fields.key });
  return acc._signer;
}

async function modelsOf(acc) {
  const s = await signer(acc);
  const req = s.prepareRequest('https://api3.qoder.sh', '/algo/api/v2/model/list', 'GET', undefined);
  const r = await get(req.url, req.headers);
  if (r.status !== 200) throw new Error('models ' + r.status + ' ' + r.body.slice(0, 120));
  return JSON.parse(r.body).chat || [];
}

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

http.createServer(async (req, res) => {
  try {
    const auth = req.headers['authorization'] || '';
    if (auth !== 'Bearer ' + KEY) return json(res, 401, { error: { message: 'bad key' } });
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/v1/models') {
      const acc = accounts[0];
      const models = await modelsOf(acc);
      return json(res, 200, { object: 'list', data: models.map(m => ({ id: m.key, object: 'model', owned_by: 'qoder' })) });
    }
    if (u.pathname === '/v1/chat/completions' && req.method === 'POST') {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const openaiBody = JSON.parse(Buffer.concat(chunks).toString());
      const modelKey = openaiBody.model || 'qfmodel';
      const acc = accounts[0];
      const s = await signer(acc);
      const catalog = [];
      const info = catalog.find(m => m.key === modelKey) || { display_name: modelKey, is_reasoning: true };
      const requestId = crypto.randomUUID();
      const wire = buildWire({
        modelKey, modelConfig: modelConfigFor(modelKey, info.display_name, info.is_reasoning),
        openai: openaiBody, requestId, sessionId: crypto.randomUUID(),
      });
      const signed = s.prepareInfer('https://api3.qoder.sh', JSON.stringify(wire), modelKey, 'system');

      const stream = openaiBody.stream !== false;
      const upstream = await new Promise((resolve, reject) => {
        const uu = new URL(signed.url);
        const opts = { hostname: uu.hostname, port: 443, path: uu.pathname + uu.search, method: 'POST',
          headers: { host: uu.hostname, ...signed.headers, 'content-length': Buffer.byteLength(signed.body) } };
        if (PINS) opts.lookup = (h, o, cb) => PIN_MAP[h] ? (o && o.all ? cb(null, [{ address: PIN_MAP[h], family: 4 }]) : cb(null, PIN_MAP[h], 4)) : dns.lookup(h, o, cb);
        const rq = https.request(opts, resolve); rq.on('error', reject); rq.write(signed.body); rq.end();
      });
      if (upstream.statusCode !== 200) {
        const b = []; for await (const c of upstream) b.push(c);
        return json(res, 502, { error: { message: 'upstream ' + upstream.statusCode + ' ' + Buffer.concat(b).toString().slice(0, 300) } });
      }
      if (!stream) {
        let agg = '', reasoning = '', finish = null, usage = null, buf2 = '';
        const tools = [];
        for await (const chunk of upstream) {
          buf2 += chunk.toString();
          let idx2;
          while ((idx2 = buf2.indexOf('\n')) >= 0) {
            const line = buf2.slice(0, idx2).trim(); buf2 = buf2.slice(idx2 + 1);
            if (!line.startsWith('data:')) continue;
            const inner = unwrapEnvelope(line.slice(5).trim());
            if (!inner) continue;
            if (inner.usage) usage = inner.usage;
            const ch = inner.choices && inner.choices[0];
            if (!ch) continue;
            if (ch.finish_reason) finish = ch.finish_reason;
            const d = ch.delta || {};
            if (d.content) agg += d.content;
            if (d.reasoning_content) reasoning += d.reasoning_content;
            if (d.tool_calls) for (const tc of d.tool_calls) {
              const i = tc.index || 0;
              tools[i] = tools[i] || { id: tc.id, type: 'function', function: { name: '', arguments: '' } };
              if (tc.function && tc.function.name) tools[i].function.name += tc.function.name;
              if (tc.function && tc.function.arguments) tools[i].function.arguments += tc.function.arguments;
            }
          }
        }
        const msg = { role: 'assistant', content: agg };
        if (reasoning) msg.reasoning_content = reasoning;
        if (tools.length) msg.tool_calls = tools.filter(Boolean);
        return json(res, 200, {
          id: 'chatcmpl-' + requestId.slice(0, 16), object: 'chat.completion',
          created: Math.floor(Date.now() / 1000), model: modelKey,
          choices: [{ index: 0, message: msg, finish_reason: finish || 'stop' }],
          usage: usage ? { prompt_tokens: usage.prompt_tokens || 0, completion_tokens: usage.completion_tokens || 0, total_tokens: usage.total_tokens || 0 } : undefined,
        });
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      let buf = '';
      let content = '';
      let usage = null;
      for await (const chunk of upstream) {
        buf += chunk.toString();
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, idx).trim(); buf = buf.slice(idx + 1);
          if (!line.startsWith('data:')) continue;
          const inner = unwrapEnvelope(line.slice(5).trim());
          if (!inner) { if (line.includes('[DONE]')) { res.write('data: [DONE]\n\n'); } continue; }
          if (inner.usage) usage = inner.usage;
          const ch = inner.choices && inner.choices[0];
          if (ch && ch.delta && ch.delta.content) content += ch.delta.content;
          res.write('data: ' + JSON.stringify(inner) + '\n\n');
        }
      }
      res.end();
      console.log(`[chat] ${modelKey} content=${JSON.stringify(content.slice(0, 60))} usage=${JSON.stringify(usage)}`);
      return;
    }
    json(res, 404, { error: { message: 'not found' } });
  } catch (e) {
    json(res, 500, { error: { message: String(e.message || e) } });
  }
}).listen(PORT, () => console.log('test relay on :' + PORT + ' (key ' + KEY + ')'));

