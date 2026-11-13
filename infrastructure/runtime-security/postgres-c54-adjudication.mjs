import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';
import {run} from '../recovery/process.mjs';
import {loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {deriveC51Decisions, hash, PARENT_INSPECTOR_SHA256} from './postgres-c51-adjudication.mjs';
import {effectivePostgresSummary} from './ssl-refresh.mjs';

export const PARENT = 'ghcr.io/claidd/otziv-security@sha256:13d2e1ba01b593f1f284d5d7869a9994f5251f036355e90064406ca4e7d809fc';
export const PARENT_ID = 'sha256:ceffd72a35bc4a20a9588f71deaf98cc145ba6832104a8b539a63395ce341975';
const PARENT_PROOF = 'f7b0235211f16ce6927abe4c1040133fc26cc128668cc1be70c85c8bb7fd2b22';
const PG = 'otziv-postgresql-17';
const PG_VERSION = '17.12-0+deb13u1+otziv1';
const KRB_VERSION = '1.22.3+otziv1';
const PG_SOURCE = '9cf136a703d8ee2f83465c43be4e9e8e7930c82b0bfba13be7e2537be769ac55';
const KRB_SOURCE = 'fe90af8441ad9fa9b064b8e53077cefb782a96f8a68276b031dc54af1789ff0b';
const libraries = new Set(['libkrb5-3','libgssapi-krb5-2','libkrb5support0','libk5crypto3']);
const prefix = 'usr/local/share/otziv/';
const status = 'var/lib/dpkg/status';
const info = 'var/lib/dpkg/info/';
const directory = new URL('./', import.meta.url);
const digest = /^[a-f0-9]{64}$/;
const upstreamNotice = 'https://web.mit.edu/kerberos/krb5-1.22/krb5-1.22.3.html';
// PostgreSQL's four advisory pages explicitly limit these findings to major version 18.
const pg18 = new Set([116313,116318,116329,120144]);
const kerberosFixes = new Set([71199,71200,107364,112315,112316,112317,112318,112319,112320,112321,112322,112323,118380,119079]);

function rule(name, version, cve) {
  const number = Number(cve.replace(/^CVE-2026-/, ''));
  if (cve !== 'CVE-2026-' + number) return;
  if (name === PG && version === PG_VERSION && pg18.has(number))
    return 'https://www.postgresql.org/support/security/' + cve + '/';
  if (libraries.has(name) && version === KRB_VERSION && kerberosFixes.has(number)) return upstreamNotice;
}

function releaseDecisions(report) {
  const decisions = [];
  for (const [resultIndex, section] of report.Results.entries()) {
    if (section.Class !== 'os-pkgs' || section.Type !== 'debian') continue;
    for (const [findingIndex, finding] of (section.Vulnerabilities || []).entries()) {
      const name = finding.PkgName, version = finding.InstalledVersion, cve = finding.VulnerabilityID;
      const advisory = rule(name, version, cve);
      if (!advisory || finding.FixedVersion || !['HIGH','CRITICAL'].includes(finding.Severity) || finding.DataSource?.ID !== 'debian') continue;
      const packages = section.Packages.filter(p => p.Name === name);
      assert.equal(packages.length, 1, 'postgres_c54_ambiguous_scanner_package');
      const pkg = packages[0];
      assert.deepEqual([pkg.AnalyzedBy, pkg.Arch, pkg.SrcName], ['dpkg','amd64',name === PG ? 'postgresql-17' : 'krb5']);
      assert.equal([pkg.Version,pkg.Release].filter(Boolean).join('-'),version);
      assert.equal([pkg.SrcVersion,pkg.SrcRelease].filter(Boolean).join('-'),version);
      assert.equal(pkg.Identifier?.PURL, finding.PkgIdentifier?.PURL);
      assert.equal(pkg.Identifier.PURL,'pkg:deb/debian/'+name+'@'+encodeURIComponent(version)+'?arch=amd64&distro=debian-13.6');
      decisions.push({resultIndex, findingIndex, package:name, version, cve, advisory,
        state:name === PG ? 'unaffected_major_version' : 'fixed_upstream', fixedHighOrCritical:false, unfixedHighOrCritical:true});
    }
  }
  return decisions;
}

export async function validateC54Runtime(runtime, report, revision) {
  const previous = await readFile(new URL('proofs/c51-postgres/publication/vulnerabilities.runtime-c51.json.gz',directory));
  assert.equal(hash(previous), PARENT_PROOF);
  const parent = JSON.parse(gunzipSync(previous)).candidate;
  assert.equal(parent.imageId, PARENT_ID);
  assert.equal(runtime.schema, 'otziv-postgres-c54-inspection-v1');
  assert.equal(runtime.parentInspectorSha256, PARENT_INSPECTOR_SHA256);
  assert.equal(runtime.inspectorSha256, hash(await readFile(new URL('postgres-c54-inspection.py',directory))));
  const child = runtime.candidate, config = report.Metadata.ImageConfig;
  assert.equal(child.containerExecuted,false);
  assert.ok([child.reference,report.Metadata.ImageID].includes(child.imageId));
  assert.deepEqual(child.rootfs,config.rootfs.diff_ids);
  assert.deepEqual(child.rootfs.slice(0,parent.rootfs.length),parent.rootfs);
  assert.ok(child.rootfs.length > parent.rootfs.length);
  assert.match(revision,/^[a-f0-9]{40}$/);
  const labels = {...parent.configuration.Labels,
    'com.otziv.publication.revision':revision, 'com.otziv.security.proof':'postgres-17.12',
    'com.otziv.security.generation':'c54-postgres',
    'com.otziv.security.scope':'PostgreSQL 17.12 with Debian security patches and MIT Kerberos 1.22.3 client libraries'};
  const env = parent.configuration.Env.map(x => x === 'PG_VERSION=17.11' ? 'PG_VERSION=17.12' : x);
  assert.deepEqual({...child.configuration,User:child.configuration.User || ''},
    {...parent.configuration,User:parent.configuration.User || '',Env:env,Labels:labels});
  for (const [field,value] of Object.entries(child.configuration))
    assert.deepEqual(config.config[field] || null,value || null,'postgres_c54_image_config:'+field);
  for (const [path,content] of Object.entries(child.captured)) assert.equal(child.inventory[path]?.sha256, hash(content),path);
  const data = name => JSON.parse(child.captured[prefix+name]);
  const pg = data('upstream-components.json').find(p => p.name === 'postgresql');
  assert.deepEqual([pg.version,pg.packageVersion,pg.sha256,pg.debianSource.archiveSha256],
    ['17.12',PG_VERSION,PG_SOURCE,'2e02b8b27ec24abf8ee8ed3395703b02708bdf35284b65b254e84bc27351b5b6']);
  const krb = data('mit-kerberos.json');
  assert.deepEqual([krb.version,krb.packageVersion,krb.sha256,krb.signer],
    ['1.22.3',KRB_VERSION,KRB_SOURCE,'C4493CB739F4A89F9852CBC20CBA08575F8372DF']);
  for (const component of [pg,krb]) {
    const bom = data('upstream.cdx.json').components.filter(p => p.name === component.name);
    assert.equal(bom.length,1);
    assert.deepEqual([bom[0].version,bom[0].purl],[component.version,component.purl]);
    assert.equal(bom[0].externalReferences[0].hashes[0].content,component.sha256);
  }
  const before = parent.captured[status].trimEnd().split('\n\n');
  const records = before.map(row => {
    const name = row.split('\n')[0].slice('Package: '.length);
    if (name === PG) return row.replace('Version: 17.11\n','Version: '+PG_VERSION+'\n')
      .replace(/^Homepage: .+$/m,'Homepage: '+pg.url);
    return libraries.has(name) ? row.replaceAll('1.21.3-5+deb13u1',KRB_VERSION) : row;
  });
  assert.equal(child.captured[status],records.join('\n\n')+'\n','postgres_c54_unrelated_dpkg_changes');
  const list = info+PG+'.list';
  for (const [path,content] of Object.entries(parent.captured)) if (![status,list].includes(path))
    assert.equal(child.captured[path],content,'postgres_c54_package_list:'+path);
  const owned = new Set(child.captured[list].split('\n').map(path => path.replace(/^\//,'')));
  const files = data('runtime-files.json');
  const allowed = new Set([status,list,info+PG+'.md5sums','usr/share/doc/otziv-postgresql-17/copyright',
    'usr/share/doc/otziv-krb5/NOTICE',...['upstream-components.json','debian-components.json','runtime-files.json','upstream.cdx.json',
      'mit-kerberos.json','build/pg-config.txt',...['configure','build','check','install','xml2-check'].map(s=>'build/postgresql-'+s+'.log')].map(s=>prefix+s)]);
  for (const name of libraries) {
    const paths = parent.captured[info+name+':amd64.list'].split('\n').map(p=>p.replace(/^\//,''))
      .filter(p=>p.startsWith('usr/lib/x86_64-linux-gnu/') && p.includes('.so.') && parent.inventory[p]?.kind==='file');
    assert.equal(paths.length,1,'postgres_c54_parent_library:'+name);
    const path = paths[0];
    allowed.add(path);
    allowed.add(info+name+':amd64.md5sums');
    assert.equal(child.inventory[path].sha256,krb.installedLibraries[path]);
    assert.equal(child.inventory[path].sha256,files[path].sha256);
    assert.notEqual(child.inventory[path].sha256,parent.inventory[path].sha256);
  }
  for (const [path,old] of Object.entries(parent.inventory)) assert.ok(child.inventory[path],'postgres_c54_deleted:'+path);
  for (const [path,row] of Object.entries(child.inventory)) {
    if (path.startsWith('usr/local/pgsql/')) {
      assert.ok(owned.has(path) && files[path]?.upstream==='postgresql','postgres_c54_unowned:'+path);
      assert.ok(parent.inventory[path] || /^usr\/local\/pgsql\/share\/locale\/(?:zh_CN|zh_TW)\/LC_MESSAGES\/[a-z0-9_-]+\.mo$/.test(path),'postgres_c54_new_payload:'+path);
      assert.equal(row.kind==='file' ? row.sha256 : row.target,files[path].sha256 || files[path].link,path);
    } else if (!allowed.has(path)) assert.deepEqual(row,parent.inventory[path],'postgres_c54_unrelated_file:'+path);
  }
  assert.equal(child.inventory['usr/share/doc/otziv-postgresql-17/copyright'].sha256,pg.licenseSha256);
  assert.equal(child.inventory['usr/share/doc/otziv-krb5/NOTICE'].sha256,krb.licenseSha256);
  assert.notEqual(child.inventory['usr/local/pgsql/bin/postgres'].sha256,parent.inventory['usr/local/pgsql/bin/postgres'].sha256);
}

export async function c54Receipt(report, bytes, id, runtimeBytes, revision) {
  assert.match(id,/^sha256:[a-f0-9]{64}$/);
  const runtime = JSON.parse(gunzipSync(runtimeBytes));
  assert.equal(runtime.candidate.reference,id);
  await validateC54Runtime(runtime,report,revision);
  const {review,reviewSha256} = await loadPostgresC16Proof();
  const {decisions, september} = await deriveC51Decisions(report,review);
  return {schema:'otziv-postgres-c54-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',rawReportModified:false,
    rawReportSha256:hash(bytes),imageConfigId:report.Metadata.ImageID,immutableImageId:id,
    runtimeProofSha256:hash(runtimeBytes),parentRuntimeSha256:PARENT_PROOF,reviewSha256,validUntil:review.validUntil,
    libxmlSeptember:september,decisions:[...decisions,...releaseDecisions(report)]};
}

export async function adjudicateC54(report, bytes, id, scratch) {
  const output = join(scratch,'postgres-runtime-c54.json.gz');
  try {
    await run(process.platform==='win32'?'python':'python3',['-B',fileURLToPath(new URL('postgres-c54-inspection.py',directory)),
      '--candidate',id,'--output',output,'--temporary-directory',scratch],{timeoutMs:600000});
    return await c54Receipt(report,bytes,id,await readFile(output),report.Metadata.ImageConfig.config.Labels['com.otziv.publication.revision']);
  } catch (error) {
    return {schema:'otziv-postgres-c54-adjudication-v1',status:'REJECTED',decisions:[],reason:String(error.message).split('\n')[0].slice(0,180)};
  }
}

export function effectiveC54Summary(summary, receipt) {
  if (receipt.status !== 'EXACT_RUNTIME_VERIFIED') return {...summary,result:'FAIL'};
  assert.equal(receipt.parentRuntimeSha256,PARENT_PROOF);
  assert.match(receipt.runtimeProofSha256,digest);
  const current = receipt.decisions.filter(d=>d.advisory);
  for (const d of current) {
    assert.equal(d.advisory,rule(d.package,d.version,d.cve));
    assert.equal(d.state,d.package===PG?'unaffected_major_version':'fixed_upstream');
    assert.deepEqual([d.fixedHighOrCritical,d.unfixedHighOrCritical],[false,true]);
  }
  assert.equal(new Set(current.map(d=>d.package+'|'+d.cve)).size,current.length);
  const prior = effectivePostgresSummary(summary,{...receipt,decisions:receipt.decisions.filter(d=>!d.advisory),
    schema:'otziv-postgres-c23-adjudication-v1',sslRefresh:{runtimeProofSha256:PARENT_PROOF}});
  const unfixed = prior.effectiveUnfixedHighOrCritical-current.length;
  assert.ok(unfixed>=0);
  return {...prior,adjudicatedPostgresUnfixedHighOrCritical:prior.adjudicatedPostgresUnfixedHighOrCritical+current.length,
    effectiveUnfixedHighOrCritical:unfixed,unresolvedRiskReview:unfixed?'REQUIRED':'NONE'};
}
