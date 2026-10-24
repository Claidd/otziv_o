import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { brotliCompressSync, brotliDecompressSync } from 'node:zlib';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const require = createRequire('/usr/local/lib/node_modules/npm/package.json');
const fetch = require('make-fetch-happen');
const { request } = require('undici');
const CachePolicy = require('http-cache-semantics');
assert.equal(process.versions.node, '24.22.1');
assert.equal(require('./package.json').version, '11.19.1');
for (const [name, version] of Object.entries({ 'brace-expansion': '5.0.12', undici: '6.29.1', 'http-cache-semantics': '4.3.0' })) {
  assert.equal(require(`${name}/package.json`).version, version);
}
const input = Buffer.from('c40 local Node Brotli runtime');
assert.deepEqual(brotliDecompressSync(brotliCompressSync(input)), input);
assert.equal(new CachePolicy({ url: '/private', headers: {} }, {
  status: 200, headers: { 'cache-control': 'private, max-age=3600' },
}, { shared: false }).storable(), true);
assert.equal(new CachePolicy({ url: '/vary', headers: {} }, {
  status: 200, headers: { 'cache-control': 'max-age=3600', vary: '*' },
}).satisfiesWithoutRevalidation({ url: '/vary', headers: {} }), false);
let requests = 0;
const server = createServer((req, res) => {
  requests++;
  res.writeHead(200, { 'content-type': 'text/plain', 'cache-control': 'max-age=3600' });
  res.end('c40-registry-fixture');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const cachePath = mkdtempSync(join(tmpdir(), 'c40-npm-cache-'));
try {
  const url = `http://127.0.0.1:${server.address().port}/fixture`;
  assert.equal(await (await request(url)).body.text(), 'c40-registry-fixture');
  assert.equal(await (await fetch(url, { cachePath })).text(), 'c40-registry-fixture');
  const before = requests;
  const cached = await fetch(url, { cachePath, cache: 'only-if-cached' });
  assert.equal(await cached.text(), 'c40-registry-fixture');
  assert.equal(requests, before);
  console.log(JSON.stringify({ result: 'PASS', node: process.versions.node, npm: '11.19.1',
    undiciRequest: true, npmPrivateCache: true, offlineCacheHit: true, varyWildcard: true, brotliRoundTrip: true }));
} finally {
  await new Promise(resolve => server.close(resolve));
  rmSync(cachePath, { recursive: true, force: true });
}
