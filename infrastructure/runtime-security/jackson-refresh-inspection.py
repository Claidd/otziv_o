"""Stopped-image proof of one official Jackson patch in server and shaded CLI."""
import argparse,copy,gzip,hashlib,importlib.util,json,pathlib,subprocess,tempfile,uuid,zipfile
SOURCE=pathlib.Path(__file__).with_name('ssl-refresh-inspection.py')
spec=importlib.util.spec_from_file_location('retained_ssl_inspector',SOURCE);base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
SERVER='opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-databind-2.21.5.jar'
CLI='opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar'
OLD='62134a98de12d0a421cd2f5b9854c8e63f10f524b0341e66cfd31a4326f6a329'
NEW='1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1'
def selected(n):return n.startswith('com/fasterxml/jackson/databind/') or n.startswith('META-INF/maven/com.fasterxml.jackson.core/jackson-databind/')
def inspect(reference,directory):
 record=base.inspect(reference,directory);owner='otziv-jackson-inspection-'+uuid.uuid4().hex
 base.docker('create','--name',owner,'--label','otziv.jackson.inspection.owner='+owner,'--network','none','--entrypoint','/not-executed',reference)
 try:
  jars={}
  for i,name in enumerate([SERVER,CLI]):
   path=directory/(owner+'-'+str(i)+'.jar');base.docker('cp',owner+':/'+name,str(path));raw=path.read_bytes()
   assert hashlib.sha256(raw).hexdigest()==record['inventory'][name]['sha256']
   with zipfile.ZipFile(path) as jar:
    names=jar.namelist();assert len(names)==len(set(names))
    jars[name]={'sha256':hashlib.sha256(raw).hexdigest(),'entries':{x.filename:{'sha256':hashlib.sha256(jar.read(x.filename)).hexdigest(),'size':x.file_size,'mode':x.external_attr,'compression':x.compress_type} for x in jar.infolist()}}
  record['jars']=jars;return record
 finally:
  obj=json.loads(base.docker('inspect',owner))[0];assert obj['Config']['Labels']['otziv.jackson.inspection.owner']==owner and not obj['State']['Running'];base.docker('rm','-v',owner)
def verify(before,after,commit):
 expected=copy.deepcopy(before['configuration']);expected['Labels']['com.otziv.publication.revision']=commit
 assert after['configuration']==expected,'jackson_launch_changed'
 assert after['rootfs'][:len(before['rootfs'])]==before['rootfs'] and len(after['rootfs'])>len(before['rootfs']),'jackson_parent_layers_changed'
 changed=sorted(p for p in before['inventory'].keys()|after['inventory'].keys() if before['inventory'].get(p)!=after['inventory'].get(p));assert changed==sorted([SERVER,CLI]),'jackson_unrelated_file_changed'
 assert before['inventory'][SERVER]['sha256']==OLD and after['inventory'][SERVER]['sha256']==NEW,'jackson_official_bytes_mismatch'
 for path in [SERVER,CLI]:
  for k in ['mode','uid','gid','kind']:assert before['inventory'][path][k]==after['inventory'][path][k],'jackson_file_metadata_changed'
 for image in [before,after]:
  for path in [SERVER,CLI]:assert image['jars'][path]['sha256']==image['inventory'][path]['sha256']
  cli=image['jars'][CLI]['entries'];server=image['jars'][SERVER]['entries']
  reduced=lambda values:{p:{k:v[k] for k in ['sha256','size']} for p,v in values.items() if selected(p)}
  if image is after:assert reduced(cli)==reduced(server),'jackson_cli_class_or_metadata_mismatch'
 assert {p:v for p,v in before['jars'][CLI]['entries'].items() if not selected(p)}=={p:v for p,v in after['jars'][CLI]['entries'].items() if not selected(p)},'jackson_cli_unrelated_entry_changed'
 return changed
def main():
 parser=argparse.ArgumentParser()
 for k in ['parent','candidate','output','temporary-directory','publication-commit']:parser.add_argument('--'+k,required=True)
 a=parser.parse_args()
 with tempfile.TemporaryDirectory(prefix='jackson-proof-',dir=a.temporary_directory) as temporary:images=[inspect(x,pathlib.Path(temporary)) for x in [a.parent,a.candidate]]
 changed=verify(*images,a.publication_commit)
 record={'schema':'otziv-jackson-refresh-inspection-v1','result':'PASS','productionAccess':False,'ownedContainersRemaining':0,'images':images,'changedFiles':changed,'publicationCommit':a.publication_commit,'executedScriptSha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'underlyingInspectorSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest()}
 pathlib.Path(a.output).write_bytes(gzip.compress((json.dumps(record,separators=(',',':'))+'\n').encode(),mtime=0));print(json.dumps({'result':'PASS','changedFiles':len(changed)}))
if __name__=='__main__':main()
