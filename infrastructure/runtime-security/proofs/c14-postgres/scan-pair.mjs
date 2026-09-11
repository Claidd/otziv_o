import assert from 'node:assert/strict';
import { randomUUID,createHash } from 'node:crypto';
import { mkdir,readFile,readdir,writeFile } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { resolve,join } from 'node:path';
import { run,startProcess } from '../../../recovery/process.mjs';
import { assertLocalDocker } from '../../../recovery/drill.mjs';
import { TRIVY_IMAGE,summarizeReport } from '../../scan.mjs';

const [candidate,outputArg]=process.argv.slice(2);
assert.match(candidate||'',/^[-a-zA-Z0-9_./:@]+$/);assert.ok(outputArg);
const output=resolve(outputArg);await mkdir(output,{recursive:true});
assert.equal((await readdir(output)).length,0,'output_not_empty');await assertLocalDocker();
const owner='otziv-c14-postgres-scan-'+randomUUID(), label='com.otziv.c14-postgres.owner';
const source='postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d';
const docker=(args,options={})=>run('docker',args,options);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function fileHash(path){const h=createHash('sha256');for await(const chunk of createReadStream(path))h.update(chunk);return h.digest('hex');}
const cache=join(output,'cache'),scratch=join(output,'scratch');
await mkdir(cache);await mkdir(scratch);
const evidence={schema:'otziv-c14-postgres-same-db-scan-v1',owner,startedAt:new Date().toISOString(),scannerImage:TRIVY_IMAGE,
  policy:'All severities and packages retained; no ignores, no risk acceptance. Source coverage is asserted independently of findings.',images:{}};
try {
  for(const [name,image] of Object.entries({source,candidate})) {
    const directory=join(output,name), input=join(directory,'input'), results=join(directory,'results');
    await mkdir(directory);await mkdir(input);await mkdir(results);
    const inspected=JSON.parse(await docker(['image','inspect',image]))[0];
    const version=(await docker(['run','--rm','--name',owner+'-'+name+'-version','--label',`${label}=${owner}`,
      '--network','none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
      '--memory','128m','--cpus','1','--pids-limit','64','--entrypoint','postgres',inspected.Id,'--version'])).trim();
    assert.match(version,name==='source'?/PostgreSQL\) 17\.10\b/:/PostgreSQL\) 17\.11\b/);
    const archive=join(input,'image.tar');
    await docker(['save','--output',archive,inspected.Id],{timeoutMs:300000});
    const manifest=JSON.parse(await run('tar',['-xOf',archive,'manifest.json']));
    assert.equal(manifest.length,1,'ambiguous_export');
    const config=await run('tar',['-xOf',archive,manifest[0].Config]);
    const configId='sha256:'+hash(Buffer.from(config));
    const args=['run','--rm','--name',owner+'-'+name,'--label',`${label}=${owner}`,
      '--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--memory','2g','--cpus','2','--pids-limit','128',
      '--tmpfs','/tmp:rw,nosuid,size=256m','--mount',`type=bind,source=${input},target=/input,readonly`,
      '--mount',`type=bind,source=${results},target=/results`,
      '--mount',`type=bind,source=${cache},target=/cache`,'--mount',`type=bind,source=${scratch},target=/scratch`,
      '--env','TMPDIR=/scratch',...(name==='candidate'?['--network','none']:[]),TRIVY_IMAGE,
      'image','--cache-dir','/cache','--timeout','20m','--scanners','vuln','--ignorefile','/dev/null','--list-all-pkgs',
      '--format','json','--output','/results/report.json',...(name==='candidate'?['--skip-db-update']:[]),'--input','/input/image.tar'];
    const p=startProcess('docker',args,{timeoutMs:1300000});let diagnostics='';
    p.child.stderr.on('data',x=>{diagnostics=(diagnostics+x.toString('utf8')).slice(-512000);});
    p.collect();p.child.stdin.end();
    try{await p.completed;}finally{await writeFile(join(directory,'scanner.log'),diagnostics);}
    const bytes=await readFile(join(results,'report.json')),report=JSON.parse(bytes);
    assert.equal(report.Metadata.ImageID,configId,'scanned_config_mismatch');
    const db={metadata:JSON.parse(await readFile(join(cache,'db','metadata.json'))),sha256:await fileHash(join(cache,'db','trivy.db'))};
    if(name==='source')evidence.database=db;else assert.deepEqual(db,evidence.database,'scanner_database_changed');
    const pkg=report.Results.flatMap(x=>x.Packages||[]);
    const postgres=pkg.find(x=>x.SrcName==='postgresql-17'&&x.Version.startsWith(name==='source'?'17.10':'17.11'));
    assert.ok(postgres,'postgres_source_package_not_scanned');
    const findings=report.Results.flatMap(x=>x.Vulnerabilities||[]);
    const pgFindings=findings.filter(x=>x.PkgName===postgres.Name);
    if(name==='source')for(const cve of ['CVE-2026-14662','CVE-2026-6464'])
      assert.ok(pgFindings.some(x=>x.VulnerabilityID===cve&&x.Severity==='HIGH'),'known_vulnerable_source_not_detected:'+cve);
    if(name==='candidate')for(const [expected,version] of [['gzip','1.14+otziv.cve202641992.1'],['libxml2','2.15.4'],['libxslt','1.1.45']])
      assert.ok(pkg.some(x=>x.SrcName===expected&&x.Version===version),'upstream_component_not_scanned:'+expected);
    evidence.images[name]={reference:image,localId:inspected.Id,configId,postgresVersion:version,
      reportSha256:hash(bytes),summary:summarizeReport(report),postgresPackage:postgres.Name,postgresFindingIds:pgFindings.map(x=>x.VulnerabilityID)};
    console.log(JSON.stringify({name,...evidence.images[name].summary,postgresPackage:postgres.Name,postgresFindingCount:pgFindings.length}));
  }
  evidence.coverageResult='PASS';
  evidence.releaseResult='REQUIRES_EXACT_FINDING_ADJUDICATION';
}catch(error){evidence.coverageResult='FAIL';evidence.error=error.message;process.exitCode=1;}
finally{
  try {
    const remaining=(await docker(['ps','-aq','--filter',`label=${label}=${owner}`])).trim().split(/\s+/).filter(Boolean);
    for(const id of remaining) {
      assert.equal((await docker(['inspect','--format',`{{index .Config.Labels "${label}"}}`,id])).trim(),owner);
      await docker(['rm','-f','-v',id]);
    }
    evidence.cleanup='PASS';
  }catch(error){evidence.cleanup='FAIL';evidence.coverageResult='FAIL';evidence.error=error.message;process.exitCode=1;}
  evidence.finishedAt=new Date().toISOString();await writeFile(join(output,'result.json'),JSON.stringify(evidence,null,2)+'\n');
  console.log(JSON.stringify({coverageResult:evidence.coverageResult,releaseResult:evidence.releaseResult,error:evidence.error}));
}
