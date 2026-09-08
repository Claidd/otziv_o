import datetime, hashlib, json, pathlib, shutil, subprocess, uuid

ROOT=pathlib.Path(__file__).resolve().parent
PROOF=pathlib.Path('infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3').resolve()
IMAGE='sha256:367c57af165ab4e46a36eaaea592a81575e782ce4d0a1a6695b13cdc99d4be2b'
CONFIG='sha256:b1b695fb74f983c010819b25ef5f402622f20cf865fbbb31133d9a7c69b2538b'
SCANNER='aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
OWNER=uuid.uuid4().hex
for p in [ROOT,PROOF,ROOT/'input',ROOT/'cache',ROOT/'scratch',ROOT/'results']:p.mkdir(parents=True,exist_ok=True)
def docker(args,timeout=60):
 r=subprocess.run(['docker',*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
 if r.returncode:raise RuntimeError('docker_'+args[0]+'_failed')
 return r.stdout.strip()
report={'schema':'otziv-keycloak-v3-scanner-execution-v1','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'image':IMAGE,'expectedConfig':CONFIG,'scanner':SCANNER,'attempts':[]}
old_cache=pathlib.Path('.codex-tmp/security-closure-20260908/bootstrap/public-scanner-cache')
report['seededPublicDatabaseFiles']={}
for section in ['db','java-db']:
 for p in (old_cache/section).glob('*'):
  if p.is_file():
   target=ROOT/'cache'/section/p.name;target.parent.mkdir(exist_ok=True)
   shutil.copyfile(p,target)
   report['seededPublicDatabaseFiles'][section+'/'+p.name]={'sha256':hashlib.file_digest(target.open('rb'),'sha256').hexdigest(),'size':target.stat().st_size}
docker(['save','--output',str(ROOT/'input/image.tar'),IMAGE],timeout=300)
report['inputArchiveSha256']=hashlib.file_digest((ROOT/'input/image.tar').open('rb'),'sha256').hexdigest()
for memory in ['512m','1g']:
 name='otziv-kc-v3-scan-'+OWNER[:12]+'-'+memory
 args=['create','--name',name,'--label','com.otziv.kc-v3-scan-owner='+OWNER,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--tmpfs','/tmp:rw,nosuid,size=128m','--memory',memory,'--pids-limit','128',
       '--mount','type=bind,source='+str(ROOT/'input')+',target=/input,readonly','--mount','type=bind,source='+str(ROOT/'cache')+',target=/cache','--mount','type=bind,source='+str(ROOT/'scratch')+',target=/scratch','--env','TMPDIR=/scratch',
       '--mount','type=bind,source='+str(ROOT/'results')+',target=/results',SCANNER,'image','--cache-dir','/cache','--timeout','20m','--scanners','vuln','--ignorefile','/dev/null','--list-all-pkgs','--format','json','--output','/results/report.json','--input','/input/image.tar']
 cid=docker(args)
 try:
  with (PROOF/('scanner-'+memory+'.log')).open('w',encoding='utf-8') as log:
   result=subprocess.run(['docker','start','--attach',cid],stdout=log,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',timeout=1300)
  state=json.loads(docker(['inspect',cid]))[0]
  assert state['Id']==cid and state['Config']['Labels']['com.otziv.kc-v3-scan-owner']==OWNER
  attempt={'memory':memory,'exitCode':result.returncode,'oomKilled':state['State']['OOMKilled'],'stateExitCode':state['State']['ExitCode'],'scannerId':state['Image'],'command':args}
  report['attempts'].append(attempt)
  if result.returncode==0:
   raw=ROOT/'results/report.json';data=json.loads(raw.read_text(encoding='utf-8'))
   assert data['Metadata']['ImageID']==CONFIG
   assert any(x.get('Class')=='os-pkgs' and x.get('Packages') for x in data['Results'])
   assert any(x.get('Type')=='jar' and x.get('Packages') for x in data['Results'])
   shutil.copyfile(raw,PROOF/'vulnerabilities.json')
   report['rawReportSha256']=hashlib.file_digest(raw.open('rb'),'sha256').hexdigest()
   findings=[v for x in data['Results'] for v in x.get('Vulnerabilities',[])]
   report['high']=sum(v['Severity']=='HIGH' for v in findings);report['critical']=sum(v['Severity']=='CRITICAL' for v in findings)
   report['rawResult']='PASS' if report['high']==report['critical']==0 else 'FAIL'
   break
  if not state['State']['OOMKilled']:raise RuntimeError('scanner_failed_without_oom')
 finally:
  checked=json.loads(docker(['inspect',cid]))[0]
  assert checked['Id']==cid and checked['Config']['Labels']['com.otziv.kc-v3-scan-owner']==OWNER
  docker(['rm',cid])
report['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
report['scriptSha256']=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
(PROOF/'scanner-execution.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:report.get(k) for k in ['rawResult','high','critical','rawReportSha256','completedAt']}),flush=True)
if 'rawResult' not in report:raise SystemExit(1)
