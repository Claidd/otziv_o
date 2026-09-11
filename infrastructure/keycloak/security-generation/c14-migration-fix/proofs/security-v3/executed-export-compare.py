import datetime, hashlib, io, json, pathlib, subprocess, tarfile, uuid, zipfile

ROOT=pathlib.Path(__file__).resolve().parent
PROOF=pathlib.Path('infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3').resolve()
BASE='ghcr.io/claidd/otziv-security@sha256:93dd42a5379a80fc0673bc1d0c5601d3b35009c8250394b1e2b45082388c82cc'
TARGET='sha256:367c57af165ab4e46a36eaaea592a81575e782ce4d0a1a6695b13cdc99d4be2b'
OWNER=uuid.uuid4().hex
ROOT.mkdir(parents=True,exist_ok=True);PROOF.mkdir(parents=True,exist_ok=True)
def docker(args,timeout=60):
 r=subprocess.run(['docker',*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
 if r.returncode:raise RuntimeError('docker_'+args[0]+'_failed')
 return r.stdout.strip()
def digest(b):return hashlib.sha256(b).hexdigest()
images={};inventories={};changed_jars={}
for label,image in [('base',BASE),('target',TARGET)]:
 obj=json.loads(docker(['image','inspect',image]))[0]
 assert not obj['Config'].get('Volumes')
 images[label]={'id':obj['Id'],'architecture':obj['Architecture'],'os':obj['Os'],'rootfsLayers':obj['RootFS']['Layers']}
 name='otziv-kc-v3-export-'+OWNER[:10]+'-'+label
 cid=docker(['create','--name',name,'--label','com.otziv.kc-v3-export-owner='+OWNER,'--network','none','--entrypoint','/bin/true',image])
 archive=ROOT/(label+'-rootfs.tar')
 try:
  docker(['export','--output',str(archive),cid],timeout=300)
 finally:
  checked=json.loads(docker(['inspect',cid]))[0]
  assert checked['Id']==cid and checked['Config']['Labels']['com.otziv.kc-v3-export-owner']==OWNER and not checked['State']['Running'] and not checked['Mounts']
  docker(['rm',cid])
 inventory={};jars={}
 with tarfile.open(archive) as tf:
  for m in tf:
   path=m.name.removeprefix('./').rstrip('/')
   if not path:continue
   row={'type':m.type.decode('ascii'),'mode':m.mode,'uid':m.uid,'gid':m.gid}
   if m.isfile():
    f=tf.extractfile(m);row.update(size=m.size,sha256=hashlib.file_digest(f,'sha256').hexdigest())
    if path.startswith('opt/keycloak/') and path.endswith('.jar'):
     jars[path]=row['sha256']
   elif m.issym() or m.islnk():row['linkname']=m.linkname
   inventory[path]=row
  if label=='target':
   for path in ['opt/keycloak/otziv-realm-migration-provenance.json','opt/keycloak/otziv-realm-migration-proof/causal-baseline.txt','opt/keycloak/otziv-realm-migration-proof/causal-patched.txt']:
    data=tf.extractfile(path).read();(PROOF/path.split('/')[-1]).write_bytes(data)
 inventories[label]=inventory
 (PROOF/(label+'-rootfs-inventory.json')).write_text(json.dumps(inventory,sort_keys=True,indent=2)+'\n',encoding='utf-8')
 (PROOF/(label+'-keycloak-jars.json')).write_text(json.dumps(jars,sort_keys=True,indent=2)+'\n',encoding='utf-8')
excluded=['etc/hosts','etc/hostname','etc/resolv.conf']
diff=[{'path':p,'base':inventories['base'].get(p),'target':inventories['target'].get(p)} for p in sorted(set(inventories['base'])|set(inventories['target'])) if p not in excluded and inventories['base'].get(p)!=inventories['target'].get(p)]
modules=['opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-storage-private-26.7.3.jar','opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar']
allowed=lambda p:p in modules or p.startswith('opt/keycloak/lib/quarkus/') or p in ['opt/keycloak/otziv-realm-migration-provenance.json','opt/keycloak/otziv-realm-migration-proof','opt/keycloak/otziv-realm-migration-proof/causal-baseline.txt','opt/keycloak/otziv-realm-migration-proof/causal-patched.txt']
unexpected=[d['path'] for d in diff if not allowed(d['path'])]
for p in modules:
 entries={}
 for label in ['base','target']:
  with tarfile.open(ROOT/(label+'-rootfs.tar')) as tf,zipfile.ZipFile(io.BytesIO(tf.extractfile(p).read())) as z:
   entries[label]={n:digest(z.read(n)) for n in z.namelist() if not n.endswith('/')}
 changed_jars[p]={'baseCount':len(entries['base']),'targetCount':len(entries['target']),'differences':[{'entry':n,'base':entries['base'].get(n),'target':entries['target'].get(n)} for n in sorted(set(entries['base'])|set(entries['target'])) if entries['base'].get(n)!=entries['target'].get(n)]}
report={'schema':'otziv-keycloak-v3-independent-rootfs-comparison-v1','result':'PASS' if not unexpected else 'FAIL','completedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'images':images,'dockerGeneratedFilesExcluded':excluded,'mtimeExcluded':True,'changedFiles':diff,'unexpectedPaths':unexpected,'patchedJarEntryDifferences':changed_jars,'baseRootfsLayerPrefixPreserved':images['target']['rootfsLayers'][:len(images['base']['rootfsLayers'])]==images['base']['rootfsLayers'],'scriptSha256':digest(pathlib.Path(__file__).read_bytes())}
(PROOF/'rootfs-comparison.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'result':report['result'],'differences':len(diff),'unexpectedPaths':unexpected,'baseRootfsLayerPrefixPreserved':report['baseRootfsLayerPrefixPreserved'],'changedJarEntries':{p:len(r['differences']) for p,r in changed_jars.items()}}))
