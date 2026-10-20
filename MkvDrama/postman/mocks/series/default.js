// Protocol recovered from evidence/series.readable.js. All keys and URLs are invented.
const http = require('http');
const crypto = require('crypto');
const sessions = new Map();
let calls = [];
function envelope(text, key) {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
  return {d: Buffer.concat([cipher.update(text), cipher.final(), cipher.getAuthTag()]).toString('base64'),
    s: nonce.toString('hex')};
}
function send(res, status, data, headers = {}) {
  res.writeHead(status, {'Content-Type': 'application/json', ...headers});
  res.end(JSON.stringify(data));
}
http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path === '/__reset') { sessions.clear(); calls = []; return send(res, 200, {}); }
  if (path === '/__state') return send(res, 200, {calls});
  let body = typeof req.body === 'object' ? JSON.stringify(req.body) : (req.body || '');
  if (req.body === undefined) for await (const chunk of req) body += chunk;
  calls.push({method: req.method, path, body,
    userAgent: req.headers['user-agent'], accept: req.headers.accept,
    referer: req.headers.referer, origin: req.headers.origin});
  const match = path.match(/^\/(title|blocked|tampered|bad-seed|bad-cookie|plain)(\/.*)?$/);
  if (!match) return send(res, 501, {detail: 'Unmodeled endpoint'});
  const [, name, suffix = ''] = match;
  const gate = `/series/${name}/_gate`;
  if (req.method === 'GET' && !suffix) {
    if (!req.headers['user-agent']?.startsWith('Mozilla/') || !req.headers.accept?.includes('text/html'))
      return send(res, 400, {detail: 'Browser user agent and HTML Accept required'});
    const id = crypto.randomBytes(8).toString('hex');
    sessions.set(id, {key: crypto.randomBytes(32), value: crypto.randomBytes(16).toString('hex'), gated: false});
    const content = name === 'plain' ? panel(name) : '<div id="mlx-root" data-min-age="0">Preparing...</div>';
    res.writeHead(200, {'Content-Type': 'text/html', 'Set-Cookie': `_did=${id}; Path=/; HttpOnly`});
    return res.end(`<h1>Fixture title</h1>${content}`);
  }
  const id = /(?:^|;\s*)_did=([^;]+)/.exec(req.headers.cookie || '')?.[1];
  const session = sessions.get(id);
  if (!session) return send(res, 403, {detail: 'Session cookie missing'});
  if (req.method === 'POST' && suffix === '/_vb3k_mnxr_w') {
    if (!req.headers.referer || !req.headers.origin || req.headers.accept !== 'application/json')
      return send(res, 400, {detail: 'Missing access headers'});
    const field = {n: 'fixture_dynamic', v: session.value};
    const cookie = name === 'bad-cookie' ? 'invalid' : Buffer.from(JSON.stringify(
      envelope(JSON.stringify(field), session.key))).toString('base64url');
    return send(res, 200, {gate_path: name === 'bad-seed' ? '//other.example/gate' : gate,
      pass_path: `/series/${name}/_pass`, dec_key: session.key.toString('hex')},
    {'Set-Cookie': `_akx=${cookie}; Path=/`});
  }
  if (req.method === 'POST' && ['/_gate', '/_pass'].includes(suffix)) {
    let value;
    try { value = JSON.parse(body); } catch { return send(res, 400, {detail: 'Invalid JSON'}); }
    if (name === 'blocked') return send(res, 403, {detail: 'Please complete verification to load download links.'});
    if (value.fixture_dynamic !== session.value || value.r !== null || value.w !== false)
      return send(res, 403, {detail: 'Invalid dynamic field'});
    if (suffix === '/_gate') {
      if (value.i !== true) return send(res, 403, {detail: 'Missing interaction flag'});
      session.gated = true;
      res.writeHead(204); return res.end();
    }
    if (!session.gated) return send(res, 403, {detail: 'Gate not completed'});
    const key = crypto.createHash('sha256').update('access-payload:' + gate).digest();
    const payload = envelope(panel(name), key);
    if (name === 'tampered') {
      const bytes = Buffer.from(payload.d, 'base64'); bytes[0] ^= 1; payload.d = bytes.toString('base64');
    }
    return send(res, 200, payload);
  }
  if (req.method === 'GET' && suffix === '/_c/example') {
    res.writeHead(307, {Location: 'https://mega.nz/file/invented#fixture-key'}); return res.end();
  }
  return send(res, 501, {detail: 'Unmodeled endpoint'});
}).listen(process.env.PORT || 4931);

function panel(name) {
  return `<div class="soraddlx"><div class="sorattlx">NF - Episode 01</div>
    <span data-download-password hidden>fixture&amp;pass</span>
    <div class="soraurlx"><strong>1080pHD</strong>
    <a href="/series/${name}/_c/example">Link 1</a></div></div>`;
}
