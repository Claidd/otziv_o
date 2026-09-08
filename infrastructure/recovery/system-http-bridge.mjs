// Executed only inside the fixture's private runner. Credential-bearing input
// comes through stdin; callers never write responses/tokens to public evidence.
import { createHash, createHmac } from 'node:crypto';
let input = ''; for await (const chunk of process.stdin) input += chunk;
const q = JSON.parse(input), options = q.options || {};
if (q.multipart) {
  const form = new FormData(); form.append('file', new Blob([Buffer.from(q.multipart.base64, 'base64')], { type: 'image/png' }), 'fixture.png');
  options.body = form;
}
if (q.s3) {
  const url = new URL(q.url), time = new Date().toISOString().replace(/[-:]|\.\d{3}/g, ''), day = time.slice(0, 8);
  const hash = x => createHash('sha256').update(x).digest('hex');
  const hmac = (key, text) => createHmac('sha256', key).update(text).digest();
  const payload = hash(options.body || ''), scope = `${day}/us-east-1/s3/aws4_request`;
  const canonicalQuery = [...url.searchParams].sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  const headers = `host:${url.host}\nx-amz-content-sha256:${payload}\nx-amz-date:${time}\n`;
  const signed = 'host;x-amz-content-sha256;x-amz-date';
  const request = [options.method || 'GET', url.pathname, canonicalQuery, headers, signed, payload].join('\n');
  const signing = hmac(hmac(hmac(hmac('AWS4' + q.s3.secret, day), 'us-east-1'), 's3'), 'aws4_request');
  options.headers = { ...options.headers, 'x-amz-date': time, 'x-amz-content-sha256': payload,
    Authorization: `AWS4-HMAC-SHA256 Credential=${q.s3.access}/${scope}, SignedHeaders=${signed}, Signature=${hmac(signing, `AWS4-HMAC-SHA256\n${time}\n${scope}\n${hash(request)}`).toString('hex')}` };
}
const response = await fetch(q.url, { ...options, redirect: 'manual', signal: AbortSignal.timeout(25000) });
const bytes = Buffer.from(await response.arrayBuffer());
if (bytes.length > 4 * 1024 * 1024) throw Error('fixture_response_too_large');
console.log(JSON.stringify({ status: response.status, text: q.binary ? undefined : bytes.toString(),
  sha256: q.binary ? createHash('sha256').update(bytes).digest('hex') : undefined,
  bytes: bytes.length, location: response.headers.get('location'), versionId: response.headers.get('x-amz-version-id') }));
