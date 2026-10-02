'use strict';
const http = require('node:http');
const assert = require('node:assert/strict');
const { setTimeout: sleep } = require('node:timers/promises');

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

function failure(stage, url, error, status) {
  // Never include response bodies or the original exception message: inspect responses contain synthetic secrets.
  const endpoint = url.split('?')[0];
  const type = error?.name || 'Error';
  const result = new Error(`stage=${stage} endpoint=${endpoint} status=${status ?? 'unavailable'} type=${type}`);
  result.name = 'ObserverFixtureError'; result.type = type; result.status = status;
  return result;
}
function deadline(parent, milliseconds) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new DOMException('Fixture deadline expired', 'TimeoutError')), milliseconds);
  return {
    signal: parent ? AbortSignal.any([parent, controller.signal]) : controller.signal,
    close() { clearTimeout(timer); controller.abort(); },
  };
}
async function pause(milliseconds, signal, stage, url) {
  try { await sleep(milliseconds, undefined, { signal }); }
  catch (error) { throw failure(stage, url, signal.aborted ? signal.reason : error); }
}
async function get(url, stage, signal, requestMs) {
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.any([signal, AbortSignal.timeout(requestMs)]) });
    return { status: response.status, text: await response.text() };
  } catch (error) { throw failure(stage, url, error, response?.status); }
}
function check(stage, url, response, validate) {
  try { assert.equal(response.status, 200); return validate(response.text); }
  catch (error) { throw failure(stage, url, error, response.status); }
}
async function ready(url, stage, signal, requestMs, retryMs) {
  for (;;) {
    let response;
    try { response = await get(url, stage, signal, requestMs); }
    catch (error) {
      if (signal.aborted || !['TimeoutError', 'TypeError'].includes(error.type)) throw error;
    }
    if (response?.status === 200) return response;
    if (response && response.status < 500) throw failure(stage, url, new Error('Unexpected readiness status'), response.status);
    await pause(retryMs, signal, stage, url);
  }
}
async function streamContains(url, marker, signal, streamMs) {
  const bounded = deadline(signal, streamMs); let response;
  try {
    response = await fetch(url, { signal: bounded.signal });
    if (response.status !== 200) throw new Error('dozzle log stream rejected');
    let text = '';
    for await (const chunk of response.body) {
      text = (text + Buffer.from(chunk).toString('utf8')).slice(-65_536);
      if (text.includes(marker)) return;
    }
    throw new Error('fixture log missing');
  } catch (error) { throw failure('dozzle-log-stream', url, error, response?.status); }
  finally { bounded.close(); }
}

async function probe({ marker = process.env.FIXTURE_MARKER, fixtureId = process.env.FIXTURE_ID,
  observer = 'http://observer:2375', dozzle = 'http://dozzle:8080', sinkUrl = 'http://sink:3100',
  deadlineMs = 80_000, readinessMs = 20_000, requestMs = 5000, streamMs = 10_000, evidenceMs = 45_000, retryMs = 250 } = {}) {
  for (const [value, maximum] of [[deadlineMs, 80_000], [readinessMs, 20_000], [requestMs, 5000], [streamMs, 10_000], [evidenceMs, 45_000], [retryMs, 250]]) {
    assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum, 'Fixture timeout must stay within its production bound');
  }
  const total = deadline(null, deadlineMs), startup = deadline(total.signal, readinessMs);
  const infoUrl = `${observer}/info`, healthUrl = `${dozzle}/healthcheck`;
  try {
    try {
      const info = await ready(infoUrl, 'observer-readiness', startup.signal, requestMs, retryMs);
      check('observer-info', infoUrl, info, text => assert.equal(typeof JSON.parse(text).ID, 'string'));
      await ready(healthUrl, 'dozzle-readiness', startup.signal, requestMs, retryMs);
    } finally { startup.close(); }
  const inspectUrl = `${observer}/containers/${fixtureId}/json`;
  const inspected = await get(inspectUrl, 'inspect-redaction', total.signal, requestMs);
  check('inspect-redaction', inspectUrl, inspected, text => {
    const value = JSON.parse(text);
    assert.equal(value.Config.Env, undefined); assert.equal(value.Config.Cmd, undefined); assert.equal(value.Mounts, undefined);
  });
  for (const [method, path] of [['POST', '/containers/create'], ['POST', `/containers/${'a'.repeat(64)}/exec`],
    ['DELETE', `/containers/${'a'.repeat(64)}`], ['POST', '/build'], ['GET', '/volumes'], ['GET', '/containers/%2e%2e/info']]) {
    // Mutation probes use no body and nonexistent IDs, so a regression still cannot modify a real container.
    const url = `${observer}${path}`, stage = `deny-${method}`; let status;
    try {
      status = await new Promise((resolve, reject) => {
      // Keep the raw path; URL normalization must not turn the encoded traversal probe into /info.
      const destination = new URL(observer);
      const request = http.request({ hostname: destination.hostname, port: destination.port, path, method,
        timeout: requestMs, signal: total.signal }, response => {
        response.resume(); response.on('end', () => resolve(response.statusCode)); response.on('error', reject);
      });
      request.on('error', reject);
      request.on('timeout', () => request.destroy(new DOMException('Fixture request timed out', 'TimeoutError'))); request.end();
    });
    assert.equal(status, 403, `${method} ${path}`);
    } catch (error) { throw failure(stage, url, error, status); }
  }
  // Select the unique allowlisted fixture label instead of relying on a consumer's host-ID encoding.
  // Dozzle's API requires explicit log levels, just like its browser UI.
  await streamContains(`${dozzle}/api/labels/dozzle.name:${marker}/logs/stream?stdout=1&stderr=1&levels=unknown&levels=info&levels=debug&levels=warn&levels=error&levels=fatal&levels=trace`, marker, total.signal, streamMs);
  const evidenceUrl = `${sinkUrl}/fixture-evidence`, evidenceDeadline = deadline(total.signal, evidenceMs);
  try { for (;;) {
    const response = await get(evidenceUrl, 'alloy-log-push', evidenceDeadline.signal, requestMs);
    const evidence = check('alloy-log-push', evidenceUrl, response, JSON.parse);
    if (evidence.markerReceived) { console.log('Real Dozzle log stream and Alloy Loki push verified; mutation/secret-field guards passed'); return; }
    await pause(500, evidenceDeadline.signal, 'alloy-log-push', evidenceUrl);
  } } finally { evidenceDeadline.close(); }
  } finally { startup.close(); total.close(); }
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
module.exports = { decodeSnappy, probe };
if (require.main === module) {
  if (process.argv[2] === 'sink') sink();
  else if (process.argv[2] === 'probe') probe().catch(error => { console.log(`OBSERVER_FIXTURE_FAILURE ${error.message}`); });
  else throw new Error('fixture mode missing');
}
