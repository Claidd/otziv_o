import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {hash,parentConfig,validateSslInspection} from './ssl-refresh.mjs';
const fixture=new URL('./fixtures/c23-ssl/',import.meta.url);
const record=JSON.parse(gunzipSync(await readFile(new URL('postgres-runtime.json.gz',fixture))));
const child=JSON.parse(await readFile(new URL('postgres-config.json',fixture)));
const parent=await parentConfig('postgres');
const check=value=>validateSslInspection(value,'postgres',parent.config,child,parent.imageId,
  'sha256:'+ (awaitedConfigHash),{parentReference:parent.entry.reference,candidateReference:record.images[1].imageId});
// Trivy's actual config identity is independent of Docker Desktop's OCI index.
const awaitedConfigHash='271a07b997078cc77db24bd0ab3095fe4bb0f19211eb0dca5df994bd24111cf6';
test('OpenSSL overlay retains complete parent configuration and every unrelated file',()=>assert.ok(check(record).changed.length>0));
for(const [name,mutate]of [
  ['another parent reference',r=>r.images[0].reference='ghcr.io/elsewhere/image@sha256:'+'a'.repeat(64)],
  ['another inspected child',r=>r.images[1].imageId='sha256:'+'b'.repeat(64)],
  ['changed parent layer',r=>r.images[1].rootfs[0]='sha256:'+'c'.repeat(64)],
  ['changed user',r=>r.images[1].configuration.User='0'],
  ['changed environment',r=>r.images[1].configuration.Env.push('EVIL=1')],
  ['unrelated package upgrade',r=>r.images[1].captured['var/lib/dpkg/status']=r.images[1].captured['var/lib/dpkg/status'].replace('Version: 17.11','Version: 17.12')],
  ['unfixed OpenSSL version',r=>r.images[1].captured['var/lib/dpkg/status']=r.images[1].captured['var/lib/dpkg/status'].replaceAll('3.5.7-1~deb13u3','3.5.7-1~deb13u2')],
  ['unrecorded file change',r=>r.images[1].inventory['usr/local/pgsql/bin/postgres'].sha256='d'.repeat(64)],
  ['recorded application change',r=>{const p='usr/local/pgsql/bin/postgres';r.images[1].inventory[p].sha256='e'.repeat(64);r.changedFiles.push(p);r.changedFiles.sort();}],
  ['forged SSL package ownership of application code',r=>{const p='usr/local/pgsql/bin/postgres';r.images[1].inventory[p].sha256='f'.repeat(64);r.changedFiles.push(p);r.changedFiles.sort();r.images[1].captured['var/lib/dpkg/info/openssl.list']+='\n/'+p+'\n';}],
  ['executed inspected image',r=>r.images[1].containerExecuted=true],
  ['unapproved package set',r=>r.packages.gzip='9.0']
])test('OpenSSL refresh rejects '+name,()=>{
  const value=structuredClone(record);mutate(value);
  for(const image of value.images)for(const [path,text]of Object.entries(image.captured))image.inventory[path].sha256=hash(text);
  assert.throws(()=>check(value));
});
