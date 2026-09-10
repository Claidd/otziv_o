"""Offline, independent review of retained Alloy artifacts. No process/network calls."""
import datetime, gzip, hashlib, json, mmap, pathlib, struct, sys

BASE = pathlib.Path(__file__).resolve().parent.parent
SOURCE = pathlib.Path(sys.argv[1]).resolve()
OUT = pathlib.Path(sys.argv[4]).resolve()
PUB = pathlib.Path(sys.argv[2]).resolve()
ARTIFACT = pathlib.Path(sys.argv[3]).resolve()
EXPECTED = '6535f200bc605f313eae5deba4e912d35ff7236f3dd066639d8d52d8eb01e07a'
PREFIXES = ('github.com/docker/docker/daemon', 'github.com/moby/moby/daemon', 'github.com/moby/moby/v2/daemon')
inputs = {}
checks = []
def read(path):
    data = path.read_bytes()
    inputs[str(path)] = {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
    return data
def obj(path): return json.loads(read(path))
def check(name, condition):
    if not condition: raise AssertionError(name)
    checks.append(name)
def digest(data): return hashlib.sha256(data).hexdigest()
def forbidden(name): return any(name == p or name.startswith(p+'/') or name.startswith(p+'.') for p in PREFIXES)
summary = obj(SOURCE / 'closure-analysis.json')
published = obj(SOURCE / 'published/summary.json')
attempts = obj(SOURCE / 'reproduction-attempts.json')
binary = SOURCE / 'published/alloy'
h=hashlib.sha256()
with binary.open('rb') as stream:
    while data:=stream.read(8*1024*1024): h.update(data)
inputs[str(binary)]={'sha256':h.hexdigest(),'bytes':binary.stat().st_size}
check('actual_published_binary_full_sha',h.hexdigest()==EXPECTED==published['binarySha256']==summary['publishedBinarySha256'])

# Decode ELF64 little-endian Go 1.20+ pclntab directly, independently of debug/gosym.
with binary.open('rb') as stream:
    b=mmap.mmap(stream.fileno(),0,access=mmap.ACCESS_READ)
    check('elf64_little_endian',b[:6]==b'\x7fELF\x02\x01')
    shoff=struct.unpack_from('<Q',b,40)[0]
    entrysize,nsections,stringindex=struct.unpack_from('<HHH',b,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',b,shoff+i*entrysize) for i in range(nsections)]
    names_sec=sections[stringindex]; strings=b[names_sec[4]:names_sec[4]+names_sec[5]]
    pcln=[s for s in sections if strings[s[0]:strings.find(b'\0',s[0])]==b'.gopclntab']
    check('exact_one_go_function_table',len(pcln)==1)
    start,size=pcln[0][4:6]
    check('known_go_function_table_format',b[start:start+8]==bytes.fromhex('f1ffffff00000108'))
    nfunc,nfiles,textstart,namesoff,cuoff,fileoff,pcoff,pclnoff=struct.unpack_from('<QQQQQQQQ',b,start+8)
    check('function_table_offsets',72==namesoff<cuoff<fileoff<pcoff<pclnoff<size and 1000<nfunc<1000000)
    extracted=[]
    for i in range(nfunc):
        entry,funcoff=struct.unpack_from('<II',b,start+pclnoff+i*8)
        assert 0<=funcoff<size-pclnoff-8
        nameoff=struct.unpack_from('<i',b,start+pclnoff+funcoff+4)[0]
        namepos=start+namesoff+nameoff; end=b.find(b'\0',namepos)
        assert nameoff>=0 and namepos<end<start+cuoff
        extracted.append(b[namepos:end].decode('utf-8'))
    b.close()
linked_bytes=gzip.decompress(read(SOURCE/'linked-functions.raw.json.gz'))
linked=json.loads(linked_bytes)
check('raw_functions_equal_inspector_record',linked_bytes==read(SOURCE/'published/linked-functions-v2.stdout'))
check('independent_binary_functions_equal_all_retained_names',sorted(extracted)==linked['functions'] and len(extracted)==418393==linked['functionCount'])
check('no_linked_daemon_functions',not any(forbidden(n) for n in extracted))
docker_funcs=[n for n in extracted if n.startswith('github.com/docker/docker/')]
check('positive_control_actual_docker_functions_present',len(docker_funcs)==474)

raw=gzip.decompress(read(SOURCE/'packages.raw.jsons.gz'))
check('raw_package_bytes_match_reproduction',raw==read(SOURCE/'repro-output/packages.jsons') and digest(raw)==summary['rawGoListSha256'])
decoder=json.JSONDecoder(); text=raw.decode(); offset=0; packages={}
while offset<len(text):
    while offset<len(text) and text[offset].isspace(): offset+=1
    if offset==len(text): break
    p,offset=decoder.raw_decode(text,offset); name=p['ImportPath']
    assert name not in packages and not any(p.get(key) for key in ('Incomplete','Error','DepsErrors'))
    packages[name]=p
entry='github.com/grafana/alloy/otel_engine'
check('single_correct_main',packages[entry]['Name']=='main')
edges=[]
for name,p in packages.items():
    for dep in p.get('Imports',[]):
        dep=p.get('ImportMap',{}).get(dep,dep)
        assert dep=='C' or dep in packages
        edges.append([name,dep])
visited=set(); pending=[entry]
while pending:
    name=pending.pop()
    if name=='C' or name in visited: continue
    visited.add(name); p=packages[name]
    pending.extend(p.get('ImportMap',{}).get(n,n) for n in p.get('Imports',[]))
check('every_recorded_package_reachable_from_main',visited==set(packages)==set(packages[entry]['Deps'])|{entry})
check('source_counts_independently_recomputed',len(packages)==5199 and len(edges)==53420)
check('no_daemon_packages_or_import_edges',not any(forbidden(n) for n in packages) and not any(forbidden(d) for _,d in edges))
check('positive_control_cadvisor_and_docker_client_present','github.com/google/cadvisor/container/docker' in packages and 'github.com/docker/docker/client' in packages)
graph_bytes=gzip.decompress(read(SOURCE/'import-closure.json.gz'));graph=json.loads(graph_bytes)
check('stored_graph_matches_raw_go_list',graph['packages']==sorted(packages) and graph['edges']==sorted(edges) and digest(graph_bytes)==summary['importGraphSha256'])

def canonical(data): return '\n'.join(data.decode().replace('\r\n','\n').split('\n')[1:]).rstrip()+'\n'
check('canonical_buildinfo_identity',canonical(read(SOURCE/'repro-output/alloy.buildinfo.txt'))==canonical(read(SOURCE/'published/build-info.stdout')))
for label in ('root','collector'):
    for suffix in ('go.mod.before.txt','go.mod.after.txt','go.sum.before.txt','go.sum.after.txt'):
        name=label+'.'+suffix
        check('module_bytes_'+name,read(SOURCE/'repro-output'/name)==read(SOURCE/'published/build'/name))
hashes=dict((path,value) for value,path in (line.split(maxsplit=1) for line in read(SOURCE/'repro-output/build-sha256.txt').decode().splitlines()))
check('reproduced_binary_sha_matches_actual_binary',hashes['build/alloy']==EXPECTED)
for path,name in [('go.mod','root.go.mod.after.txt'),('go.sum','root.go.sum.after.txt'),('collector/go.mod','collector.go.mod.after.txt'),('collector/go.sum','collector.go.sum.after.txt')]:
    check('reproduction_source_hash_'+path,hashes[path]==digest(read(SOURCE/'repro-output'/name)))
for name in ('Makefile','.govulncheck.yaml'):
    check('reproduction_source_hash_'+name,hashes[name]==digest(read(SOURCE/'repro-output'/name)))
makefile=read(SOURCE/'upstream-Makefile')
check('vendor_makefile_identity',makefile==read(SOURCE/'repro-output/Makefile'))
check('vendor_build_includes_gore2_and_collector_main',b'override GO_TAGS := $(strip gore2regex $(GO_TAGS))' in makefile and b'cd ./collector && $(GO_ENV) go build $(GO_FLAGS) -o ../$(ALLOY_BINARY) .' in makefile)
original=read(PUB/'infrastructure/runtime-security/builds/Alloy.Dockerfile')
closure=read(SOURCE/'AlloyClosure.Dockerfile');repro=read(SOURCE/'AlloyRepro.Dockerfile')
check('original_source_dockerfile_exact',digest(original)==summary['originalDockerfileSha256'])
normalize=lambda x:x.replace(b'\r\n',b'\n').rstrip()
check('inventory_preserves_entire_original_dockerfile',normalize(closure).startswith(normalize(original)))
check('repro_preserves_entire_closure_dockerfile',repro.startswith(closure))
check('repro_dockerfile_and_supported_date_identity',digest(repro)==attempts['nextAttempt']['dockerfileSha256'] and attempts['nextAttempt']['sourceDateEpoch']==1789058980 and b'ifdef SOURCE_DATE_EPOCH' in makefile)
log=read(SOURCE/'repro-build.log').decode()
check('actual_build_log_sha_assertion_completed','build/alloy: OK' in log and '#22 DONE' in log and '#24 DONE' in log)
check('build_platform_matches_inventory',obj(SOURCE/'repro-output/go-env.json')=={'CGO_ENABLED':'1','GOARCH':'amd64','GOEXPERIMENT':'','GOOS':'linux','GOVERSION':'go1.27.1'})

primary=obj(SOURCE/'primary/index.json')
for record in primary['records']:
    check('primary_sha_'+record['file'],digest(read(SOURCE/'primary'/record['file']))==record['sha256'])
go=obj(SOURCE/'primary/GO-2026-5746.json');gh=obj(SOURCE/'primary/GHSA-rg2x-37c3-w2rh.json')
check('41567_primary_scope',go['id']=='GO-2026-5746' and 'CVE-2026-41567' in go['aliases'] and all(i['path'] in PREFIXES and i['symbols']==['Daemon.containerExtractToDir'] for a in go['affected'] for i in a['ecosystem_specific']['imports']))
check('42306_primary_scope',gh['cve_id']=='CVE-2026-42306' and {a['package']['name'] for a in gh['vulnerabilities']}=={'Docker Engine','github.com/docker/docker/daemon','github.com/moby/moby/v2/daemon'})
govuln=read(SOURCE/'upstream-govulncheck.yaml')
check('upstream_yaml_source_identity',govuln==read(SOURCE/'repro-output/.govulncheck.yaml'))
check('upstream_exact_two_advisory_corroboration',all(x in govuln for x in [b'GO-2026-5746',b'CVE-2026-41567',b'GO-2026-5617',b'CVE-2026-42306']))

index_raw=read(ARTIFACT/'registry-index.json');index=json.loads(index_raw)
manifest_raw=read(ARTIFACT/'registry-amd64-manifest.json');manifest=json.loads(manifest_raw)
config_raw=read(ARTIFACT/'registry-amd64-config.json');config=json.loads(config_raw)
report_raw=read(ARTIFACT/'vulnerabilities.json');report=json.loads(report_raw)
inspect=obj(SOURCE/'published/image-inspect.stdout')[0]
check('exact_c15_index_identity','sha256:'+digest(index_raw)==inspect['Id']=='sha256:d006cedf890ade4915a098a5362ce0cda9a3fd7ec2c02320f15e92ccfd669122')
check('oci_child_and_config_binding','sha256:'+digest(manifest_raw) in [d['digest'] for d in index['manifests']] and manifest['config']['digest']=='sha256:'+digest(config_raw))
check('raw_scanner_config_binding',report['Metadata']['ImageID']=='sha256:'+digest(config_raw) and report['Metadata']['ImageConfig']==config)
check('copied_image_full_rootfs_and_labels_match_scan',inspect['RootFS']['Layers']==config['rootfs']['diff_ids'] and inspect['Config']['Labels']==config['config']['Labels'])
check('raw_report_identity',digest(report_raw)==published['rawReportSha256']=='1f44717cabdad957dc27390e229dcf0d6fa6122cd184a9d64c018393c9a73197')
matches=[{'target':r['Target'],'type':r['Type'],'id':v['VulnerabilityID'],'package':v['PkgName'],'version':v['InstalledVersion'],'purl':v['PkgIdentifier']['PURL']} for r in report['Results'] for v in r.get('Vulnerabilities',[]) if v['VulnerabilityID'] in ('CVE-2026-41567','CVE-2026-42306')]
check('exact_two_current_scanner_matches',len(matches)==2 and {m['id'] for m in matches}=={'CVE-2026-41567','CVE-2026-42306'} and all(m['target']=='usr/bin/alloy' and m['type']=='gobinary' and m['purl']=='pkg:golang/github.com/docker/docker@v28.5.2%2Bincompatible' for m in matches))
target=obj(SOURCE/'target-proof/result.json')
target_image=obj(SOURCE/'target-proof/image.stdout')[0]
target_container=obj(SOURCE/'target-proof/container.stdout')[0]
target_binary=SOURCE/'target-proof/alloy';target_hash=hashlib.sha256()
with target_binary.open('rb') as stream:
    while data:=stream.read(8*1024*1024): target_hash.update(data)
inputs[str(target_binary)]={'sha256':target_hash.hexdigest(),'bytes':target_binary.stat().st_size}
check('exact_scanner_target_binary_hash',target['target']=='usr/bin/alloy' and target_hash.hexdigest()==target['binarySha256']==EXPECTED and target_binary.stat().st_size==binary.stat().st_size)
check('exact_target_image_identity',target['reference']==published['reference'] and target_image['Id']==inspect['Id'] and target_image['RootFS']==inspect['RootFS'] and target_image['Config']['Labels']==inspect['Config']['Labels'])
check('target_container_stopped_and_owned',target_container['State']['Running'] is False and target_container['Config']['Labels']['com.otziv.proof.owner']==target_container['Name'].lstrip('/'))
check('target_cleanup_identity',read(SOURCE/'target-proof/create.stdout').strip()==read(SOURCE/'target-proof/cleanup.stdout').strip()==target_container['Id'].encode())
read(SOURCE/'verify-target.py')
result={'schema':'otziv-independent-alloy-review-v1','verificationMode':'AUTOMATED_SECOND_PARSER','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'result':'PASS_EXACT_ARTIFACT_TWO_CVE_NON_AFFECTED_SCOPE','checks':checks,'checkCount':len(checks),'binarySha256':EXPECTED,'packageCount':len(packages),'importEdges':len(edges),'functionCount':len(extracted),'daemonPackages':0,'daemonFunctions':0,'dockerFunctionsPositiveControl':len(docker_funcs),'scannerMatches':matches,'limitations':['Go function-table absence alone does not cover inlining; matching complete source import closure supplies that evidence.','This proves only this exact Alloy artifact lacks these two daemon scopes; no Docker Engine, other artifact/CVE, blanket module or deployment-risk exemption.','No network, builds, scans, binary execution or shared source changes in independent verification.'],'inputs':inputs}
result['inputs'] = {(('source/' + str(pathlib.Path(k).relative_to(SOURCE))) if pathlib.Path(k).is_relative_to(SOURCE) else ('publication/' + str(pathlib.Path(k).relative_to(ARTIFACT))) if pathlib.Path(k).is_relative_to(ARTIFACT) else ('repository/' + str(pathlib.Path(k).relative_to(PUB)))):v for k,v in inputs.items()}
(OUT/'verification.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:result[k] for k in ['result','checkCount','packageCount','importEdges','functionCount','binarySha256']}))
