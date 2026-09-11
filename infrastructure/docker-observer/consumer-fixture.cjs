'use strict';
const http = require('node:http');
const assert = require('node:assert/strict');

// Bounded raw Snappy block decoder for the Loki fixture's protobuf request body.
// The test sink never accepts more than 2 MiB or evaluates provider data.
function decodeSnappy(source) {
  let position = 0, size = 0, shift = 0;
  for (;;) {
    if (position >= source.length || shift > 28) throw new Error('invalid snappy length');
    const byte = source[position++]; size += (byte & 127) * 2 ** shift;
    if (!(byte & 128)) break; shift += 7;
  }
  if (size > 2 * 1024 * 1024) throw new Error('snappy output too large');
  const output = Buffer.alloc(size); let written = 0;
  const byte = () => { if (position >= source.length) throw new Error('truncated snappy'); return source[position++]; };
  while (position < source.length) {
    const tag = byte(), type = tag & 3; let length, offset;
    if (type === 0) {
      length = tag >>> 2;
      if (length < 60) length++;
      else { const count = length - 59; length = 0; for (let i = 0; i < count; i++) length += byte() * 2 ** (i * 8); length++; }
      if (position + length > source.length || written + length > size) throw new Error('invalid literal');
      source.copy(output, written, position, position + length); position += length; written += length;
    } else {
      if (type === 1) { length = 4 + ((tag >>> 2) & 7); offset = ((tag & 224) << 3) + byte(); }
      else { length = 1 + (tag >>> 2); offset = 0; for (let i = 0; i < (type === 2 ? 2 : 4); i++) offset += byte() * 2 ** (i * 8); }
      if (!offset || offset > written || written + length > size) throw new Error('invalid copy');
      for (let i = 0; i < length; i++) { output[written] = output[written - offset]; written++; }
    }
  }
  if (written !== size) throw new Error('snappy length mismatch');
  return output;
}

async function get(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
  return { status: response.status, text: await response.text() };
}
async function streamContains(url, marker) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 10_000);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (response.status !== 200) throw new Error('dozzle log stream rejected');
    let text = '';
    for await (const chunk of response.body) {
      text = (text + Buffer.from(chunk).toString('utf8')).slice(-65_536);
      if (text.includes(marker)) return;
    }
    throw new Error('fixture log missing');
  } finally { clearTimeout(timer); controller.abort(); }
}

async function probe() {
  const marker = process.env.FIXTURE_MARKER, fixtureId = process.env.FIXTURE_ID;
  const info = JSON.parse((await get('http://observer:2375/info')).text);
  assert.equal(typeof info.ID, 'string');
  assert.equal((await get('http://dozzle:8080/healthcheck')).status, 200);
  const inspected = JSON.parse((await get(`http://observer:2375/containers/${fixtureId}/json`)).text);
  assert.equal(inspected.Config.Env, undefined); assert.equal(inspected.Config.Cmd, undefined); assert.equal(inspected.Mounts, undefined);
  for (const [method, path] of [['POST', '/containers/create'], ['POST', `/containers/${'a'.repeat(64)}/exec`],
    ['DELETE', `/containers/${'a'.repeat(64)}`], ['POST', '/build'], ['GET', '/volumes'], ['GET', '/containers/%2e%2e/info']]) {
    // Mutation probes use no body and nonexistent IDs, so a regression still cannot modify a real container.
    const status = await new Promise((resolve, reject) => {
      const request = http.request({ hostname: 'observer', port: 2375, path, method, timeout: 5000 }, response => {
        response.resume(); response.on('end', () => resolve(response.statusCode));
      });
      request.on('error', reject); request.on('timeout', () => request.destroy(new Error('fixture timeout'))); request.end();
    });
    assert.equal(status, 403, `${method} ${path}`);
  }
  // Select the unique allowlisted fixture label instead of relying on a consumer's host-ID encoding.
  // Dozzle's API requires explicit log levels, just like its browser UI.
  await streamContains(`http://dozzle:8080/api/labels/dozzle.name:${marker}/logs/stream?stdout=1&stderr=1&levels=unknown&levels=info&levels=debug&levels=warn&levels=error&levels=fatal&levels=trace`, marker);
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    const evidence = JSON.parse((await get('http://sink:3100/fixture-evidence')).text);
    if (evidence.markerReceived) { console.log('Real Dozzle log stream and Alloy Loki push verified; mutation/secret-field guards passed'); return; }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('Alloy did not forward the synthetic log');
}

function sink() {
  let markerReceived = false;
  http.createServer(async (request, response) => {
    if (request.url === '/fixture-evidence') { response.setHeader('Content-Type', 'application/json'); return response.end(JSON.stringify({ markerReceived })); }
    if (request.url !== '/loki/api/v1/push' || request.method !== 'POST') { response.writeHead(404); return response.end(); }
    let bytes = 0; const chunks = [];
    try {
      for await (const chunk of request) { bytes += chunk.length; if (bytes > 2 * 1024 * 1024) throw new Error('fixture body too large'); chunks.push(chunk); }
      const body = Buffer.concat(chunks);
      const decoded = request.headers['content-type']?.includes('json') ? body : decodeSnappy(body);
      markerReceived ||= decoded.includes(Buffer.from(process.env.FIXTURE_MARKER));
      response.writeHead(204); response.end();
    } catch { response.writeHead(400); response.end(); }
  }).listen(3100, '0.0.0.0');
}
module.exports = { decodeSnappy };
if (require.main === module) {
  if (process.argv[2] === 'sink') sink();
  else if (process.argv[2] === 'probe') probe().catch(error => { console.log(`OBSERVER_FIXTURE_FAILURE ${error.message}`); });
  else throw new Error('fixture mode missing');
}
