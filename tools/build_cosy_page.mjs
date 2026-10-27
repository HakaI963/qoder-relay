// Builds app/src/main/assets/cosy_page.html: classic-script glue + inlined wasm (base64) + signing API.
// Run after updating the cosy module: node tools/build_cosy_page.mjs
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(dir, '..');

let glue = fs.readFileSync(path.join(dir, 'cosy', 'auth_impl.mjs'), 'utf8');

const exportMatch = glue.match(/export\{([^}]+)\};?\s*$/);
if (!exportMatch) throw new Error('export block not found in glue');

const aliases = exportMatch[1].split(',').map(s => s.trim()).map(item => {
    const [local, exported] = item.includes(' as ') ? item.split(' as ').map(x => x.trim()) : [item, item];
    return `${exported}: ${local}`;
});

glue = glue.slice(0, exportMatch.index);
// classic-script compatibility: import.meta is module-only (used solely for a fallback wasm path we never take)
glue = glue.replaceAll('import.meta.url', '"https://cosy.local/"');
const wasmB64 = fs.readFileSync(path.join(dir, 'cosy', 'auth.wasm')).toString('base64');

const page = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>cosy</title></head><body>
<script>
${glue}
window.QoderAuth = { ${aliases.join(', ')} };
</script>
<script>
(function(){
  var B64 = "${wasmB64}";
  var A = window.QoderAuth;
  window.__state = 'loading';
  window.__ctx = null;
  window.__mid = ''; window.__uid = ''; window.__enc = ''; window.__key = '';
  function bytesFromB64(b64){
    var bin = atob(b64), n = bin.length, out = new Uint8Array(n);
    for (var i = 0; i < n; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  Promise.resolve().then(function(){
    return A.default({ module_or_path: bytesFromB64(B64) });
  }).then(function(){
    window.__state = 'ready';
  }).catch(function(e){
    window.__state = 'error: ' + String(e && e.message ? e.message : e);
  });

  window.__genFields = function(jsonStr){
    try { return JSON.stringify({ ok: true, out: A.generate_runtime_auth_fields(jsonStr) }); }
    catch (e) { return JSON.stringify({ ok: false, err: String(e && e.message || e) }); }
  };
  window.__reset = function(mid, uid, enc, key){
    try { if (window.__ctx) { try { window.__ctx.free(); } catch(e){} window.__ctx = null; } } catch(e){}
    window.__mid = mid; window.__uid = uid; window.__enc = enc; window.__key = key;
    return 'ok';
  };
  function ctx(){
    if (!window.__ctx) {
      window.__ctx = new A.QoderContext(window.__mid, '1.1.79',
        JSON.stringify({ uid: window.__uid, encrypt_user_info: window.__enc, key: window.__key }));
    }
    return window.__ctx;
  }
  window.__signInfer = function(endpoint, bodyJson, modelKey, modelSource){
    try {
      if (window.__state !== 'ready') return JSON.stringify({ ok:false, err: window.__state });
      var r = ctx().prepareInferRequest(endpoint, bodyJson, modelKey, modelSource);
      var headers = {};
      r.headers.forEach(function(v,k){ headers[k]=v; });
      var out = { ok:true, url:r.url, headers:headers, body:r.body };
      try { r.free(); } catch(e){}
      return JSON.stringify(out);
    } catch (e) { return JSON.stringify({ ok:false, err:String(e && e.message || e) }); }
  };
  window.__signRequest = function(endpoint, path, method){
    try {
      if (window.__state !== 'ready') return JSON.stringify({ ok:false, err: window.__state });
      var r = ctx().prepareRequest(endpoint, path, method, 'auth', undefined, undefined);
      var headers = {};
      r.headers.forEach(function(v,k){ headers[k]=v; });
      var out = { ok:true, url:r.url, headers:headers, body:'' };
      try { r.free(); } catch(e){}
      return JSON.stringify(out);
    } catch (e) { return JSON.stringify({ ok:false, err:String(e && e.message || e) }); }
  };
})();
</script>
</body></html>`;

const out = path.join(root, 'app', 'src', 'main', 'assets', 'cosy_page.html');
fs.writeFileSync(out, page);
console.log('wrote', out, (page.length / 1024).toFixed(0) + 'KB');
