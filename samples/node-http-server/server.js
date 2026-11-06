'use strict';
const http = require('http');
const host = process.env.HOST || '127.0.0.1';
const port = Number(process.env.PORT || 8000);
const appName = process.env.LOCALHOST_APP_NAME || 'Node Local Server';

const server = http.createServer((req, res) => {
  if (req.url === '/api/info') {
    const body = JSON.stringify({ app: appName, runtime: 'Node.js', node: process.version, message: 'Local server online' });
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
    res.end(body);
    return;
  }
  const html = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>${appName}</title>
  <style>body{font:16px system-ui;background:#0a1119;color:#eef7f5;padding:32px;max-width:760px;margin:auto}main{background:#12202d;padding:24px;border-radius:18px}code{color:#70e0c0}</style>
  </head><body><main><h1>Node.js runtime is running</h1><p>This page is served by the embedded Node mobile engine.</p><pre id="info">Loading…</pre></main>
  <script>fetch('/api/info').then(r=>r.json()).then(x=>document.getElementById('info').textContent=JSON.stringify(x,null,2))</script></body></html>`;
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(html) });
  res.end(html);
});
server.listen(port, host, () => console.log(`${appName} listening on ${host}:${port}`));
