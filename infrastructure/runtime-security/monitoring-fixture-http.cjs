// Isolated synthetic source used by the monitoring storage upgrade proof.
const http = require('node:http');
const fs = require('node:fs');
const stateFile = '/tmp/fixture-state.json';
const cursorCounts = {};
const server = http.createServer(async (request, response) => {
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  if (request.url === '/cursor-evidence') {
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(cursorCounts));
  } else if (request.url === '/cursor-reset' && request.method === 'POST') {
    for (const key of Object.keys(cursorCounts)) delete cursorCounts[key]; response.end('reset');
  } else if (request.url === '/loki/api/v1/push' && request.method === 'POST') {
    try {
      let size = 0; const chunks = [];
      for await (const chunk of request) { size += chunk.length; if (size > 2 * 1024 * 1024) throw Error('too large'); chunks.push(chunk); }
      const decoded = require('/consumer-fixture.cjs').decodeSnappy(Buffer.concat(chunks));
      for (const marker of decoded.toString('utf8').match(/OTZIV_CURSOR_[a-f0-9]{32}_(?:before|after|rollback)/g) || []) cursorCounts[marker] = (cursorCounts[marker] || 0) + 1;
      response.writeHead(204); response.end();
    } catch { response.writeHead(400); response.end(); }
  } else if (request.url.startsWith('/actuator/prometheus')) {
    response.setHeader('Content-Type', 'text/plain; version=0.0.4');
    response.end(`fixture_upgrade_value{phase="${state.phase}"} ${state.phase === 'before' ? 17 : 29}\n`);
  } else if (request.url.startsWith('/api/v1/query')) {
    if (request.headers.authorization !== 'Basic ' + Buffer.from('fixture:' + state.password).toString('base64')) {
      response.writeHead(401); response.end('fixture credential required'); return;
    }
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ status: 'success', data: { resultType: 'vector', result: [{ metric: { fixture: 'credential' }, value: [Date.now() / 1000, '17'] }] } }));
  } else { response.writeHead(404); response.end(); }
});
server.listen(8080, '0.0.0.0');
