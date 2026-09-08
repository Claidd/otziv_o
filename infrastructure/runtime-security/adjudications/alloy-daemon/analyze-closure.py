import datetime, gzip, hashlib, json, pathlib, re

BASE = pathlib.Path(__file__).resolve().parent
OUT = BASE / 'repro-output'
def sha(b): return hashlib.sha256(b).hexdigest()
def canonical(b): return '\n'.join(b.decode().replace('\r\n','\n').split('\n')[1:]).rstrip() + '\n'
assert not (BASE / 'closure-analysis.json').exists()
published = json.loads((BASE / 'published/summary.json').read_bytes())
hashes = dict((path, value) for value, path in (line.split(maxsplit=1) for line in (OUT / 'build-sha256.txt').read_text().splitlines()))
assert hashes['build/alloy'] == published['binarySha256'], 'reproduced_binary_does_not_match_published'
functions_bytes = (BASE / 'published/linked-functions-v2.stdout').read_bytes()
linked = json.loads(functions_bytes)
assert linked['schema'] == 'otziv-go-linked-functions-v1'
assert linked['binarySha256'] == published['binarySha256']
assert sha(functions_bytes) == published['linkedFunctionsSha256']
assert linked['functionCount'] == len(linked['functions']) == published['linkedFunctions']
assert all(isinstance(n,str) and n for n in linked['functions'])
assert linked['functions'] == sorted(linked['functions'])
assert linked['inspectedBinaryExecuted'] is False
daemon_prefixes = ['github.com/docker/docker/daemon','github.com/moby/moby/daemon','github.com/moby/moby/v2/daemon']
daemon_functions = [n for n in linked['functions'] if any(n.startswith(p + '.') or n.startswith(p + '/') for p in daemon_prefixes)]
assert len(daemon_functions) == published['daemonFunctions'] == 0
docker_functions = [n for n in linked['functions'] if n.startswith('github.com/docker/docker/')]
assert len(docker_functions) == published['dockerModuleFunctions'] > 0
primary = json.loads((BASE / 'primary/index.json').read_bytes())
for item in primary['records']:
    assert sha((BASE / 'primary' / item['file']).read_bytes()) == item['sha256']
go_scope = json.loads((BASE / 'primary/GO-2026-5746.json').read_bytes())
assert go_scope['id'] == 'GO-2026-5746' and 'CVE-2026-41567' in go_scope['aliases']
go_affected = [a for a in go_scope['affected'] if a['package']['name'] == 'github.com/docker/docker']
assert len(go_affected) == 1 and go_affected[0]['ecosystem_specific']['imports'] == [{'path':'github.com/docker/docker/daemon','symbols':['Daemon.containerExtractToDir']}]
gh_scope = json.loads((BASE / 'primary/GHSA-rg2x-37c3-w2rh.json').read_bytes())
assert gh_scope['ghsa_id'] == 'GHSA-rg2x-37c3-w2rh' and gh_scope['cve_id'] == 'CVE-2026-42306'
assert 'github.com/docker/docker/daemon' in [a['package']['name'] for a in gh_scope['vulnerabilities']]

assert canonical((OUT / 'alloy.buildinfo.txt').read_bytes()) == canonical((BASE / 'published/build-info.stdout').read_bytes()), 'build_info_mismatch'
for label in ['root', 'collector']:
    for suffix in ['go.mod.before.txt','go.mod.after.txt','go.sum.before.txt','go.sum.after.txt']:
        name = label + '.' + suffix
        assert (OUT / name).read_bytes() == (BASE / 'published/build' / name).read_bytes(), 'module_provenance_mismatch_' + name
assert (OUT / '.govulncheck.yaml').read_bytes() == (BASE / 'upstream-govulncheck.yaml').read_bytes()
env = json.loads((OUT / 'go-env.json').read_bytes())
assert env == {'CGO_ENABLED':'1','GOARCH':'amd64','GOEXPERIMENT':'','GOOS':'linux','GOVERSION':'go1.27.1'}, 'unexpected_build_platform'
raw = (OUT / 'packages.jsons').read_bytes()
assert 1000 < len(raw) < 512*1024*1024
text = raw.decode(); decoder = json.JSONDecoder(); offset = 0; packages = {}
while offset < len(text):
    while offset < len(text) and text[offset].isspace(): offset += 1
    if offset == len(text): break
    package, offset = decoder.raw_decode(text, offset)
    name = package['ImportPath']
    assert name not in packages, 'duplicate_package'
    assert not package.get('Incomplete') and not package.get('Error') and not package.get('DepsErrors'), 'incomplete_package_' + name
    packages[name] = package
assert len(packages) > 1000 and packages['github.com/grafana/alloy/otel_engine']['Name'] == 'main'
assert 'github.com/docker/docker/client' in packages and 'github.com/google/cadvisor/container/docker' in packages
assert set(packages['github.com/grafana/alloy/otel_engine']['Deps']) | {'github.com/grafana/alloy/otel_engine'} == set(packages), 'main_deps_do_not_cover_recorded_closure'
blocked = ['github.com/docker/docker/daemon','github.com/moby/moby/daemon','github.com/moby/moby/v2/daemon']
assert not [name for name in packages if any(name == b or name.startswith(b + '/') for b in blocked)], 'daemon_package_present'
edges = []
for name, package in packages.items():
    for dependency in package.get('Imports', []):
        resolved = package.get('ImportMap',{}).get(dependency, dependency)
        assert resolved in packages or resolved == 'C', 'missing_import_' + name + '_' + resolved
        edges.append([name, resolved])
graph = {'schema':'otziv-go-import-closure-v1','entrypoint':'github.com/grafana/alloy/otel_engine','packages':sorted(packages),'edges':sorted(edges),'rawGoListSha256':sha(raw),'sourceRawBytes':len(raw),'errors':[]}
graph_bytes = (json.dumps(graph,indent=2)+'\n').encode()
(BASE / 'import-closure.json.gz').write_bytes(gzip.compress(graph_bytes, mtime=0))
(BASE / 'packages.raw.jsons.gz').write_bytes(gzip.compress(raw, mtime=0))
functions = (BASE / 'published/linked-functions-v2.stdout').read_bytes()
(BASE / 'linked-functions.raw.json.gz').write_bytes(gzip.compress(functions, mtime=0))
summary = {'schema':'otziv-c12-alloy-closure-analysis-v1','createdAt':datetime.datetime.now(datetime.UTC).isoformat(),'publishedBinarySha256':published['binarySha256'],'reproducedBinaryByteIdentical':True,'moduleProvenanceByteIdentical':True,'buildInfoByteIdenticalIgnoringFilename':True,'packageCount':len(packages),'importEdges':len(edges),'daemonPackages':0,'linkedFunctions':linked['functionCount'],'daemonFunctions':len(daemon_functions),'sourceArchiveSha256':'69efdb87a91bb538323f5ccb6bcd86240cee3a78ee97632bd1005c434344c7f1','originalDockerfileSha256':'713509a4d988d813b1c2738a3ee6d896c386d2d350594998c9934357b46c9915','rawGoListSha256':sha(raw),'importGraphSha256':sha(graph_bytes),'decision':'EXACT_PUBLISHED_ARTIFACT_HAS_NO_DAEMON_PACKAGE; INDEPENDENT_REVIEW_REQUIRED_BEFORE_GATE_INTEGRATION','files':{p.name:{'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in [BASE/'import-closure.json.gz',BASE/'packages.raw.jsons.gz',BASE/'linked-functions.raw.json.gz']}}
(BASE / 'closure-analysis.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary))
