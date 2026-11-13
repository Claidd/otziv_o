"""Overlay the installed upstream release on the accepted runtime, maintaining its package and SBOM records."""
import hashlib
import json
import pathlib
import shutil
import subprocess

root = pathlib.Path('/stage')
overlay = pathlib.Path('/overlay')
inventory = overlay / 'usr/local/share/otziv'
parent = pathlib.Path('/parent')
package = 'otziv-postgresql-17'
source = 'https://ftp.postgresql.org/pub/source/v17.12/postgresql-17.12.tar.bz2'
archive_hash = hashlib.sha256(pathlib.Path('/build/postgresql.tar.bz2').read_bytes()).hexdigest()
assert archive_hash == '9cf136a703d8ee2f83465c43be4e9e8e7930c82b0bfba13be7e2537be769ac55'

def save(name, value):
    (inventory / name).write_text(json.dumps(value, indent=2) + '\n')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

sample = root / 'usr/local/pgsql/share/postgresql.conf.sample'
s = sample.read_text()
assert s.count("#listen_addresses = 'localhost'") == 1
sample.write_text(s.replace("#listen_addresses = 'localhost'", "listen_addresses = '*'"))
for p in root.rglob('*'):
    if p.is_file() and (p.suffix in ('.la', '.a') or '/include/' in str(p) or '/pkgconfig/' in str(p)):
        p.unlink()

pg = pathlib.Path('usr/local/pgsql')
files = {str(p.relative_to(root)) for p in (root / pg).rglob('*') if p.is_file() or p.is_symlink()}
old_files = json.loads((parent / 'inventory/runtime-files.json').read_text())
previous = {k for k in old_files if k.startswith(str(pg) + '/')}
missing = previous - files
assert not missing, 'Inherited obsolete PostgreSQL files: ' + repr(sorted(missing)[:20])
info = root / 'DEBIAN'
info.mkdir()
control = (parent / 'status').read_text()
records = control.strip().split('\n\n')
original = next(r for r in records if r.startswith('Package: ' + package + '\n'))
assert '\nVersion: 17.11\n' in original
version = subprocess.check_output(['dpkg-parsechangelog','-S','Version'], text=True).strip()
assert version == '17.12-0+deb13u1'
package_version = version + '+otziv1'
record = original.replace('\nVersion: 17.11\n',f'\nVersion: {package_version}\n')
record = '\n'.join('Homepage: ' + source if l.startswith('Homepage: ') else l for l in record.splitlines())
(info/'control').write_text('\n'.join(l for l in record.splitlines() if not l.startswith('Status: '))+'\n')
md5 = ''.join(hashlib.md5((root/name).read_bytes()).hexdigest()+'  '+name+'\n' for name in sorted(files) if not (root/name).is_symlink())
(info/'md5sums').write_text(md5)
deb = pathlib.Path('/tmp/postgres.deb')
subprocess.run(['dpkg-deb','--root-owner-group','--build',str(root),str(deb)],check=True)
shutil.copytree(root/pg, overlay/pg, symlinks=True)
db = overlay/'var/lib/dpkg'
(db/'info').mkdir(parents=True)
(db/'status').write_text('\n\n'.join(record if r==original else r for r in records)+'\n')
(db/'info'/f'{package}.md5sums').write_text(md5)
allpaths = sorted('/'+str(p.relative_to(root)) for p in (root/pg).rglob('*'))
(db/'info'/f'{package}.list').write_text('/.\n/usr\n/usr/local\n/usr/local/pgsql\n'+'\n'.join(allpaths)+'\n')
copyright = overlay/'usr/share/doc'/package/'copyright'
copyright.parent.mkdir(parents=True)
shutil.copyfile('COPYRIGHT',copyright)
components = json.loads((parent/'inventory/upstream-components.json').read_text())
item = next(i for i in components if i['name']=='postgresql')
item.update(version='17.12',packageVersion=package_version,url=source,sha256=archive_hash,debSha256=sha(deb),licenseSha256=sha(copyright),
            purl='pkg:generic/postgresql@17.12',cpe='cpe:2.3:a:postgresql:postgresql:17.12:*:*:*:*:*:*:*')
item['debianSource']={'version':version,'archiveSha256':sha(pathlib.Path('/build/debian.tar.xz'))}
save('upstream-components.json', components)
sbom = json.loads((parent/'inventory/upstream.cdx.json').read_text())
component = next(i for i in sbom['components'] if i['name']=='postgresql')
component.update(version=item['version'],purl=item['purl'],cpe=item['cpe'])
component['externalReferences']=[{'type':'distribution','url':source,'hashes':[{'alg':'SHA-256','content':archive_hash}]}]
for prop in component['properties']:
    if prop['name']=='otziv:runtimeDebSha256': prop['value']=item['debSha256']
    if prop['name']=='otziv:packageVersion': prop['value']=package_version
save('upstream.cdx.json', sbom)
for name in files:
    p=root/name
    old_files[name]={'upstream':'postgresql','sha256':sha(p)} if not p.is_symlink() else {'upstream':'postgresql','link':str(p.readlink())}
old_files[str(copyright.relative_to(overlay))]={'upstream':'postgresql','sha256':sha(copyright)}
save('runtime-files.json', old_files)
