// Executes the generated cosy_page.html scripts in a VM and verifies WASM init + signing calls.
import fs from 'fs';
import vm from 'vm';
import { createRequire } from 'module';
import path from 'path';
import { fileURLToPath } from 'url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(dir, '..', 'app', 'src', 'main', 'assets', 'cosy_page.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
console.log('inline scripts:', scripts.length);

const sandbox = {
    window: {},
    atob: (s) => Buffer.from(s, 'base64').toString('latin1'),
    btoa: (s) => Buffer.from(s, 'latin1').toString('base64'),
    crypto: globalThis.crypto,
    performance: globalThis.performance,
    console,
    setTimeout, clearTimeout, queueMicrotask,
    TextEncoder, TextDecoder, WebAssembly,
    require: createRequire(import.meta.url),
    fetch: () => { throw new Error('network disabled in sandbox'); },
    URL,
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
const ctx = vm.createContext(sandbox);

for (const src of scripts) {
    try { vm.runInContext(src, ctx, { timeout: 60000 }); }
    catch (e) { console.error('SCRIPT ERROR:', e.message); process.exit(1); }
}

await new Promise(r => setTimeout(r, 3000));
const state = vm.runInContext('window.__state', ctx);
console.log('state:', state);
if (state !== 'ready') process.exit(1);

const gen = vm.runInContext('window.__genFields(JSON.stringify({accessToken:"selftest",refreshToken:"x",uid:"",machineId:"11111111-1111-1111-1111-111111111111"}))', ctx);
const genO = JSON.parse(gen);
console.log('genFields ok:', genO.ok, 'keylen:', genO.ok ? JSON.parse(genO.out).key.length : genO.err);

vm.runInContext('window.__reset("11111111-1111-1111-1111-111111111111","","","")', ctx);
const sign = vm.runInContext(`window.__signRequest("https://api3.qoder.sh", "/algo/api/v2/model/list", "GET")`, ctx);
const signO = JSON.parse(sign);
console.log('signRequest ok:', signO.ok, signO.ok ? ('url: ' + signO.url) : ('err: ' + signO.err));
if (signO.ok) {
    console.log('auth header prefix:', (signO.headers.Authorization || '').slice(0, 30));
}
