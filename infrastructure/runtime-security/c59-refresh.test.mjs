import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {validateC59ActivationScan} from './c59-refresh.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';

const folder='infrastructure/runtime-security/proofs/c59-nginx/publication/';
async function fixture(){
  const read=path=>readFile(new URL('../../'+path,import.meta.url));
  const publication=JSON.parse(await read(folder+'publication.json'));
  const raw=JSON.parse(await read(folder+'vulnerabilities.json'));
  const receipt=JSON.parse(await read(folder+'vulnerabilities.adjudications.json'));
  const validate=async()=>{
    const bytes=Buffer.from(JSON.stringify(raw));
    receipt.rawReportSha256=createHash('sha256').update(bytes).digest('hex');
    publication.security={...combinedScanSummary(summarizeReport(raw),{grafana:receipt,alloy:receipt.alloy}),scannerImage:TRIVY_IMAGE};
    return validateC59ActivationScan(publication,folder+'publication.json',async path=>path.endsWith('/vulnerabilities.json')?bytes:Buffer.from(JSON.stringify(receipt)));
  };
  return {raw,validate};
}
test('C59 published server and coupled modules retain a clean scan',async()=>{await (await fixture()).validate();});
test('C59 rejects old server even when the scanner misses its vulnerability',async()=>{
  const f=await fixture();f.raw.Results.find(r=>r.Class==='os-pkgs').Packages.find(p=>p.Name==='nginx').Version='1.30.6-r1';
  await assert.rejects(f.validate(),/c59_fixed_version:nginx/);
});
test('C59 rejects a missing module from the published package inventory',async()=>{
  const f=await fixture();const os=f.raw.Results.find(r=>r.Class==='os-pkgs');os.Packages=os.Packages.filter(p=>p.Name!=='nginx-module-njs');
  await assert.rejects(f.validate(),/c59_package_coverage:nginx-module-njs/);
});
test('C59 new high findings remain blocking',async()=>{
  const f=await fixture();f.raw.Results[0].Vulnerabilities=[{VulnerabilityID:'CVE-2099-1234',Severity:'HIGH',FixedVersion:'2'}];
  await assert.rejects(f.validate(),/c50_raw_findings/);
});
