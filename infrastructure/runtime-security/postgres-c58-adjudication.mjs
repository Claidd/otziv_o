import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';
import {run} from '../recovery/process.mjs';
import {loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {deriveC51Decisions, hash, PARENT_INSPECTOR_SHA256} from './postgres-c51-adjudication.mjs';
import {effectivePostgresSummary} from './ssl-refresh.mjs';

export const PARENT = 'ghcr.io/claidd/otziv-security@sha256:32adf590dacac8d07c6dcd5bfbad27d527ae2ddf18e9822a404ba52ce69524a2';
export const PARENT_ID = 'sha256:71222076c468b230cd512a77e23c71bf3910afc46f40b4f68806808dac291549';
const PARENT_PROOF = 'fd338d9585d1281587158f4899f6c7b68e968eaaf33f636500f0627a5ea8bdcc';
const PG = 'otziv-postgresql-17';
const PG_VERSION = '17.12-0+deb13u1+otziv1';
const KRB_VERSION = '1.22.3+otziv1';
const PG_SOURCE = '9cf136a703d8ee2f83465c43be4e9e8e7930c82b0bfba13be7e2537be769ac55';
const KRB_SOURCE = 'fe90af8441ad9fa9b064b8e53077cefb782a96f8a68276b031dc58af1789ff0b';
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
      assert.equal(packages.length, 1, 'postgres_c58_ambiguous_scanner_package');
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

// Inherit source-level decisions only after the full stopped-container inventory
// proves that C54's server, libraries, package records and configuration are intact.
export async function validateC58Runtime(runtime, report, revision) {
  const previous = await readFile(new URL('proofs/c54-postgres/publication/vulnerabilities.runtime-c54.json.gz',directory));
  assert.equal(hash(previous), PARENT_PROOF, 'postgres_c58_parent_runtime');
  const parent = JSON.parse(gunzipSync(previous)).candidate;
  assert.equal(parent.imageId, PARENT_ID);
  assert.equal(runtime.schema, 'otziv-postgres-c58-inspection-v1');
  assert.equal(runtime.parentInspectorSha256, PARENT_INSPECTOR_SHA256);
  assert.equal(runtime.inspectorSha256, hash(await readFile(new URL('postgres-c58-inspection.py',directory))));
  assert.equal(runtime.inspectedBinaryExecuted, false);
  const child = runtime.candidate, config = report.Metadata.ImageConfig;
  assert.equal(child.containerExecuted, false);
  assert.ok([child.reference,report.Metadata.ImageID].includes(child.imageId));
  assert.deepEqual(child.rootfs,config.rootfs.diff_ids);
  assert.deepEqual(child.rootfs.slice(0,parent.rootfs.length),parent.rootfs);
  assert.ok(child.rootfs.length > parent.rootfs.length);
  assert.match(revision,/^[a-f0-9]{40}$/);
  const labels = {...parent.configuration.Labels,
    'com.otziv.publication.revision':revision, 'com.otziv.security.proof':'postgres-c58-gosu-go1274',
    'com.otziv.gosu.go':'1.27.4'};
  assert.deepEqual({...child.configuration,User:child.configuration.User || ''},
    {...parent.configuration,User:parent.configuration.User || '',Labels:labels});
  for (const [field,value] of Object.entries(child.configuration))
    assert.deepEqual(config.config[field] || null,value || null,'postgres_c58_config:'+field);
  assert.deepEqual(child.captured,parent.captured,'postgres_c58_package_records_changed');
  const changed = [...new Set([...Object.keys(parent.inventory),...Object.keys(child.inventory)])]
    .filter(path=>JSON.stringify(parent.inventory[path])!==JSON.stringify(child.inventory[path])).sort();
  assert.deepEqual(changed,['usr/local/bin/gosu'],'postgres_c58_unrelated_files_changed');
  const binary = child.inventory['usr/local/bin/gosu'];
  assert.deepEqual([binary.kind,binary.mode,binary.uid,binary.gid],['file',0o755,0,0]);
  assert.match(binary.sha256,digest);
  assert.notEqual(binary.sha256,parent.inventory['usr/local/bin/gosu'].sha256);
  const info = runtime.gosuBuildInfo;
  assert.match(info,/^\/tmp\/gosu: go1\.27\.4\r?$/m,'postgres_c58_go_version');
  for (const [key,value] of [['CGO_ENABLED','0'],['GOOS','linux'],['GOARCH','amd64'],['-trimpath','true']])
    assert.ok(info.split('\n').some(line=>line.trim()==='build\t'+key+'='+value),'postgres_c58_go_build:'+key);
  assert.match(info,/^\s*path\s+github\.com\/tianon\/gosu\r?$/m);
  const scanned = report.Results.filter(result=>result.Class==='lang-pkgs' && result.Type==='gobinary');
  assert.equal(scanned.length,1,'postgres_c58_go_coverage');
  assert.match(scanned[0].Target,/(?:^|\/)usr\/local\/bin\/gosu$/);
  const stdlib = scanned[0].Packages.filter(pkg=>pkg.Name==='stdlib');
  assert.equal(stdlib.length,1);
  assert.equal(stdlib[0].Version,'v1.27.4','postgres_c58_scanned_go_version');
}

export async function c58Receipt(report, bytes, id, runtimeBytes, revision) {
  assert.match(id,/^sha256:[a-f0-9]{64}$/);
  const runtime = JSON.parse(gunzipSync(runtimeBytes));
  assert.equal(runtime.candidate.reference,id);
  await validateC58Runtime(runtime,report,revision);
  const {review,reviewSha256} = await loadPostgresC16Proof();
  const {decisions, september} = await deriveC51Decisions(report,review);
  return {schema:'otziv-postgres-c58-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',rawReportModified:false,
    rawReportSha256:hash(bytes),imageConfigId:report.Metadata.ImageID,immutableImageId:id,
    runtimeProofSha256:hash(runtimeBytes),parentRuntimeSha256:PARENT_PROOF,reviewSha256,validUntil:review.validUntil,
    libxmlSeptember:september,decisions:[...decisions,...releaseDecisions(report)]};
}

export async function adjudicateC58(report, bytes, id, scratch) {
  const output = join(scratch,'postgres-runtime-c58.json.gz');
  try {
    await run(process.platform==='win32'?'python':'python3',['-B',fileURLToPath(new URL('postgres-c58-inspection.py',directory)),
      '--candidate',id,'--output',output,'--temporary-directory',scratch],{timeoutMs:600000});
    return await c58Receipt(report,bytes,id,await readFile(output),report.Metadata.ImageConfig.config.Labels['com.otziv.publication.revision']);
  } catch (error) {
    return {schema:'otziv-postgres-c58-adjudication-v1',status:'REJECTED',decisions:[],reason:String(error.message).split('\n')[0].slice(0,180)};
  }
}

export function effectiveC58Summary(summary, receipt) {
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
