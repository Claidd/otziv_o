import datetime, hashlib, io, json, pathlib, subprocess, tarfile, uuid, zipfile
ROOT=pathlib.Path(__file__).resolve().parent
OUT=pathlib.Path('infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3').resolve()
V2='sha256:6d71d32d0e4d810f8b54c1cdbe8e2d9c6264f016f2663581f5f79a6a720afd91'
V3='sha256:367c57af165ab4e46a36eaaea592a81575e782ce4d0a1a6695b13cdc99d4be2b'
PATHS=['opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar','opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-storage-private-26.7.3.jar','opt/keycloak/lib/quarkus/generated-bytecode.jar','opt/keycloak/lib/quarkus/transformed-bytecode.jar','opt/keycloak/lib/quarkus/quarkus-application.dat']
OWNER=uuid.uuid4().hex
def docker(args):
 p=subprocess.run(['docker',*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 if p.returncode:raise RuntimeError('docker_'+args[0]+'_failed')
 return p.stdout.strip()
def sha(b):return hashlib.sha256(b).hexdigest()
meta=json.loads(docker(['image','inspect',V2]))[0]
assert not meta['Config'].get('Volumes')
cid=docker(['create','--name','otziv-kc-v2-compare-'+OWNER[:10],'--label','com.otziv.kc-v3-compare-owner='+OWNER,'--network','none','--entrypoint','/bin/true',V2])
rows=[]
try:
 with tarfile.open(ROOT/'target-rootfs.tar') as tf:
  for path in PATHS:
   output=ROOT/('v2-'+path.split('/')[-1]);docker(['cp',cid+':/'+path,str(output)])
   a=output.read_bytes();b=tf.extractfile(path).read()
   row={'path':path,'v2Sha256':sha(a),'v3Sha256':sha(b),'bytesEqual':a==b}
   if path.endswith('.jar'):
    def entries(data):
     with zipfile.ZipFile(io.BytesIO(data)) as z:return {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}
    x=entries(a);y=entries(b)
    diff=[{'entry':n,'v2Sha256':x.get(n),'v3Sha256':y.get(n)} for n in sorted(x.keys()|y.keys()) if x.get(n)!=y.get(n)]
    row.update(v2EntryCount=len(x),v3EntryCount=len(y),entryDifferences=diff)
   rows.append(row)
finally:
 inspected=json.loads(docker(['inspect',cid]))[0]
 assert inspected['Id']==cid and inspected['Config']['Labels']['com.otziv.kc-v3-compare-owner']==OWNER and not inspected['State']['Running'] and not inspected['Mounts']
 docker(['rm',cid])
report={'schema':'otziv-keycloak-v2-v3-selected-payload-comparison-v1','completedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'v2Image':V2,'v3Image':V3,'scope':'Five selected files from stopped images; no services started.','files':rows,'allFiveFilesByteEqual':all(r['bytesEqual'] for r in rows),'scriptSha256':sha(pathlib.Path(__file__).read_bytes())}
(OUT/'v2-v3-selected-payload-comparison.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'allFiveFilesByteEqual':report['allFiveFilesByteEqual'],'files':[{'path':r['path'],'bytesEqual':r['bytesEqual'],'changedEntries':len(r.get('entryDifferences',[]))} for r in rows]}))
