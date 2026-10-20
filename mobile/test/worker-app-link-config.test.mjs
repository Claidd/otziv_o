import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import test from 'node:test';

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
const statement = JSON.parse(read('../../frontend/public/.well-known/assetlinks.json'));
const manifest = read('../android/app/src/main/AndroidManifest.xml');

test('the public association belongs only to the current Android release signer', () => {
  assert.equal(statement.length, 1);
  assert.deepEqual(statement[0].relation, ['delegate_permission/common.handle_all_urls']);
  assert.equal(statement[0].target.namespace, 'android_app');
  assert.equal(statement[0].target.package_name, 'com.hunt.otziv');
  assert.deepEqual(statement[0].target.sha256_cert_fingerprints, [
    '00:E2:49:AB:3D:6A:15:F0:51:C7:0F:66:B7:26:04:7F:CC:53:4F:C3:58:E4:09:A2:09:9B:4C:4C:46:1F:92:18'
  ]);
});

test('Android claims only the HTTPS worker path', () => {
  const filters = [...manifest.matchAll(/<intent-filter\s+android:autoVerify="true">([\s\S]*?)<\/intent-filter>/g)];
  assert.equal(filters.length, 1);
  assert.match(filters[0][1], /android:scheme="https"/);
  assert.match(filters[0][1], /android:host="o-ogo\.ru"/);
  assert.match(filters[0][1], /android:path="\/worker"/);
  assert.doesNotMatch(filters[0][1], /android:pathPrefix=|android:scheme="http"/);
});

for (const name of ['prod.conf', 'prod-local.conf']) {
  test(`${name} serves the verification JSON exactly without an SPA fallback`, () => {
    const config = read(`../../infrastructure/nginx/${name}`);
    const location = config.match(/location = \/\.well-known\/assetlinks\.json \{([\s\S]*?)\n    \}/);
    assert.ok(location);
    assert.match(location[1], /root \/usr\/share\/nginx\/html;/);
    assert.match(location[1], /try_files \$uri =404;/);
    assert.match(location[1], /default_type application\/json;/);
    assert.doesNotMatch(location[1], /index\.html|proxy_pass|return 30[12]/);
  });
}
