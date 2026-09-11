#!/usr/bin/env python3
"""Assemble an immutable runtime, preserving ownership/version data for every
imported Debian file. This is a new rootfs, not a package-database scrub of an
existing image. Upstream components are installed as real, separately built debs.
The full per-file inventory makes partial Debian package imports explicit.
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess

root = pathlib.Path('/runtime')
root.mkdir()
for path in ['usr/bin', 'usr/sbin', 'usr/lib', 'usr/lib64', 'etc', 'tmp', 'var/tmp',
             'var/lib/postgresql/data', 'var/run/postgresql', 'docker-entrypoint-initdb.d',
             'usr/local/share/otziv', 'var/lib/dpkg/info', 'var/lib/dpkg/updates']:
    (root / path).mkdir(parents=True, exist_ok=True)
for path, target in {'bin': 'usr/bin', 'sbin': 'usr/sbin', 'lib': 'usr/lib', 'lib64': 'usr/lib64', 'run': 'var/run'}.items():
    (root / path).symlink_to(target)
os.chmod(root/'tmp', 0o1777)
os.chmod(root/'var/tmp', 0o1777)
for path in ['var/lib/postgresql', 'var/lib/postgresql/data', 'var/run/postgresql']:
    os.chown(root/path, 999, 999)
    os.chmod(root/path, 0o3777 if path == 'var/run/postgresql' else 0o1777)

files = {}
def copy(source):
    src = pathlib.Path(source)
    # Normalize usr-merge parent symlinks, while retaining library aliases.
    parent = src.parent.resolve()
    src = parent / src.name
    rel = str(src).lstrip('/')
    dst = root / rel
    if rel in files:
        return
    if src.is_symlink():
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.is_symlink() or dst.exists(): dst.unlink()
        dst.symlink_to(os.readlink(src))
        files[rel] = {'source': str(src), 'symlink': os.readlink(src)}
        if src.exists(): copy(os.path.normpath(os.path.join(str(src.parent), os.readlink(src))))
    elif src.is_dir():
        dst.mkdir(parents=True, exist_ok=True)
        for child in src.iterdir(): copy(child)
    elif src.is_file():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        files[rel] = {'source': str(src), 'sha256': hashlib.sha256(src.read_bytes()).hexdigest()}
    else:
        raise RuntimeError('runtime_seed_missing:' + str(src))

# Exact commands used by the unchanged official entrypoint, initdb and diagnostic
# tools. File-copy/ACL/archive utilities are not silently imported as dependencies.
commands = ['bash','dash','env','id','mkdir','chmod','chown','find','ls','cat','getent',
            'locale','sort','rm','mktemp','sleep','touch','stat','readlink','dirname',
            'basename','sha256sum','tee','xzcat','xz','zstd','awk','grep','ldd',
            'openssl','true','false','printf','pwd','date','head','tail','wc']
for command in commands:
    found = shutil.which(command)
    if not found: raise RuntimeError('runtime_command_missing:' + command)
    copy(found)
copy('/usr/bin/sh')
for path in ['/etc/passwd','/etc/group','/etc/nsswitch.conf','/etc/hosts','/etc/host.conf',
             '/etc/resolv.conf','/etc/ld.so.conf','/etc/ld.so.conf.d','/etc/ssl',
             '/usr/lib/ssl','/etc/security','/etc/protocols','/etc/services',
             '/etc/os-release','/etc/debian_version','/usr/lib/locale','/usr/share/locale/locale.alias',
             '/usr/share/zoneinfo','/usr/share/terminfo','/usr/lib/x86_64-linux-gnu/security',
             '/usr/lib/x86_64-linux-gnu/libnss_wrapper.so',
             '/usr/local/bin/gosu','/usr/local/bin/docker-entrypoint.sh',
             '/usr/local/bin/docker-ensure-initdb.sh','/usr/local/bin/docker-enforce-initdb.sh',
             '/usr/local/share/doc/gosu/LICENSE']:
    copy(path)

# PostgreSQL's unconfigured PAM service resolves through other/common-*. The
# omitted login/su/runuser configurations belong to absent login utilities.
for path in pathlib.Path('/etc/pam.d').iterdir():
    if path.name in ['other','postgresql'] or path.name.startswith('common-'): copy(path)

# dlopen-based providers are not visible in ldd. Retain the original encoding,
# TLS and host authentication configuration/modules when shipped by the base.
for path in ['/usr/lib/x86_64-linux-gnu/gconv','/usr/lib/x86_64-linux-gnu/ossl-modules',
             '/usr/lib/x86_64-linux-gnu/engines-3','/etc/ldap','/etc/selinux','/etc/localtime','/etc/timezone']:
    if pathlib.Path(path).exists(): copy(path)

# First install actual upstream deb artifacts, with their authentic upstream
# versions. No fictitious Debian security version or original-package relabeling.
(root/'var/lib/dpkg/status').write_text('')
debs = sorted(str(p) for p in pathlib.Path('/packages').glob('*.deb'))
subprocess.run(['dpkg', '--root='+str(root), '--unpack', *debs], check=True)
subprocess.run(['dpkg', '--root='+str(root), '--configure', '-a'], check=True)
for p in root.rglob('*'):
    rel = str(p.relative_to(root))
    if p.is_file() and rel not in files and not rel.startswith('var/lib/dpkg/'):
        files[rel] = {'upstream': True, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}

# Resolve every ELF in the imported payload, including contrib and PAM modules.
# Newly copied libraries are themselves traversed until the closure is stable.
scanned = set()
while True:
    pending = [rel for rel in files if rel not in scanned]
    if not pending: break
    for rel in pending:
        scanned.add(rel)
        path = root / rel
        if path.is_symlink() or not path.is_file(): continue
        with path.open('rb') as f:
            if f.read(4) != b'\x7fELF': continue
        # Equivalent built files reside in /usr/local, and Debian files retain
        # their original path. ldd inspects only trusted, pinned local build code.
        source = files[rel].get('source', '/' + rel)
        result = subprocess.run(['ldd', source], text=True, capture_output=True)
        if 'not found' in result.stdout: raise RuntimeError('missing_library:' + source + '\n' + result.stdout)
        for library in re.findall(r'(?:=>\s+)?(/[^\s()]+)', result.stdout): copy(library)

# Preserve distro package records for all copied files, including dependencies
# and generated locale archives. Every selected file has explicit provenance.
owners = {}
for listing in pathlib.Path('/var/lib/dpkg/info').glob('*.list'):
    package = listing.name[:-5]
    for name in listing.read_text(errors='strict').splitlines():
        try:
            path=pathlib.Path(name)
            owners[str(path.parent.resolve()/path.name)] = package
        except (OSError, RuntimeError): pass
selected = set()
for rel, entry in files.items():
    if 'source' not in entry: continue
    source = entry['source']
    path=pathlib.Path(source)
    package = owners.get(str(path.parent.resolve()/path.name))
    if source.startswith('/usr/lib/locale/'): package = 'locales'
    if source in ['/etc/passwd','/etc/group']: package = 'base-passwd'
    if package:
        entry['debianPackage'] = package
        selected.add(package)
    else:
        entry['imageGenerated'] = True

records = pathlib.Path('/var/lib/dpkg/status').read_text().strip().split('\n\n')
selected_records = []
inventory = []
for record in records:
    fields = dict(re.findall(r'^([^\s:]+): (.*)$', record, flags=re.M))
    name = fields['Package']
    if name not in selected and name+':'+fields.get('Architecture','') not in selected: continue
    if name in ['gzip','libxml2','libxslt1.1','postgresql-17','postgresql-client-17']:
        raise RuntimeError('obsolete_component_reintroduced:' + name)
    selected_records.append(record)
    inventory.append({**fields, 'importMode': 'exact runtime files listed in runtime-files.json'})
    for candidate in pathlib.Path('/var/lib/dpkg/info').glob(name+'*'):
        if candidate.is_file() and re.fullmatch(re.escape(name)+r'(?::[^.]+)?\.(list|md5sums)',candidate.name):
            target=root/'var/lib/dpkg/info'/candidate.name
            if candidate.suffix == '.list':
                present=[line for line in candidate.read_text().splitlines() if (root/line.lstrip('/')).exists()]
                target.write_text('\n'.join(present)+'\n')
            else: shutil.copy2(candidate,target)
    copyright_path=pathlib.Path('/usr/share/doc')/name/'copyright'
    if copyright_path.exists(): copy(copyright_path)
with (root/'var/lib/dpkg/status').open('a') as stream:
    stream.write('\n\n'+'\n\n'.join(selected_records)+'\n')

subprocess.run(['ldconfig','-r',str(root)],check=True)
evidence = root/'usr/local/share/otziv'
(evidence/'runtime-files.json').write_text(json.dumps(files, indent=2, sort_keys=True)+'\n')
(evidence/'debian-components.json').write_text(json.dumps(inventory, indent=2, sort_keys=True)+'\n')
upstream=json.load(open('/build/upstream-components.json'))
for component in upstream:
    deb=pathlib.Path('/packages')/f"{component['package']}_{component.get('packageVersion',component['version'])}_amd64.deb"
    component['debSha256']=hashlib.sha256(deb.read_bytes()).hexdigest()
    source=pathlib.Path('/sources')/f"{component['name']}-{component['version']}"
    license_source=next((source/name for name in ['COPYING','Copyright','COPYRIGHT','LICENSE'] if (source/name).is_file()),None)
    if license_source is None: raise RuntimeError('upstream_license_missing:'+component['name'])
    license_target=root/'usr/share/doc'/component['package']/'copyright'
    license_target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(license_source,license_target)
    component['licenseSha256']=hashlib.sha256(license_source.read_bytes()).hexdigest()
    files[str(license_target.relative_to(root))]={'upstream':component['name'],'sha256':component['licenseSha256']}
(evidence/'runtime-files.json').write_text(json.dumps(files, indent=2, sort_keys=True)+'\n')
(evidence/'upstream-components.json').write_text(json.dumps(upstream,indent=2)+'\n')
shutil.copytree('/build/evidence', evidence/'build',dirs_exist_ok=True)
sbom={'bomFormat':'CycloneDX','specVersion':'1.6','version':1,'components':[]}
for item in upstream:
    sbom['components'].append({'type':'application' if item['name'] in ['postgresql','gzip'] else 'library',
      'name':item['name'],'version':item['version'],'purl':item['purl'],'cpe':item['cpe'],
      'externalReferences':[{'type':'distribution','url':item['url'],'hashes':[{'alg':'SHA-256','content':item['sha256']}]}],
      'properties':[{'name':'otziv:runtimeDebSha256','value':item['debSha256']},
                    {'name':'otziv:packageVersion','value':item.get('packageVersion',item['version'])},
                    {'name':'otziv:upstreamPatches','value':json.dumps(item.get('patches',[]),sort_keys=True)}]})
(evidence/'upstream.cdx.json').write_text(json.dumps(sbom,indent=2)+'\n')
print(json.dumps({'debianComponents':len(inventory),'runtimeFiles':len(files),'upstreamComponents':len(upstream)}))
subprocess.run(['chroot',str(root),'/bin/bash','-c',
    'set -e; export PATH=/usr/local/pgsql/bin:/usr/local/bin:/usr/bin:/bin; '
    'awk "BEGIN { exit 0 }"; '
    'locale -a | grep -Fx en_US.utf8; postgres --version; '
    'test ! -e /usr/bin/perl; test ! -e /usr/bin/mount; test ! -e /usr/bin/nsenter; '
    'test ! -e /usr/lib/x86_64-linux-gnu/libxml2.so.2; '
    'ldd /usr/local/pgsql/bin/postgres /usr/local/pgsql/bin/psql /usr/local/lib/libxml2.so.16 > /tmp/linker-check; '
    '! grep "not found" /tmp/linker-check; rm /tmp/linker-check'],check=True)
