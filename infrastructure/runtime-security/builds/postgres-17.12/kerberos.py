"""Install the four client libraries already present in the parent, with source and binary evidence."""
import hashlib
import json
import pathlib
import shutil

root = pathlib.Path('/overlay')
info = root / 'var/lib/dpkg/info'
status = root / 'var/lib/dpkg/status'
records = status.read_text().strip().split('\n\n')
inventory = root / 'usr/local/share/otziv'
def read(name):
    path = inventory / name
    if not path.exists(): path = pathlib.Path('/parent/inventory') / name
    return json.loads(path.read_text())
def save(name, obj):
    (inventory / name).write_text(json.dumps(obj, indent=2) + '\n')
def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()
files = read('runtime-files.json')
packages = read('debian-components.json')
sbom = read('upstream.cdx.json')
packages_to_upgrade = ['libkrb5-3', 'libgssapi-krb5-2', 'libkrb5support0', 'libk5crypto3']
version = '1.22.3+otziv1'
archive = pathlib.Path('/build/kerberos.tar.gz')
source_sha = digest(archive)
assert source_sha == 'fe90af8441ad9fa9b064b8e53077cefb782a96f8a68276b031dc54af1789ff0b'
source = 'https://web.mit.edu/kerberos/dist/krb5/1.22/krb5-1.22.3.tar.gz'
built = {}
for name in packages_to_upgrade:
    # Reuse exactly the multiarch library file owned by this package in the parent.
    listing = (pathlib.Path('/parent/info') / (name + ':amd64.list')).read_text().splitlines()
    payload = [p.lstrip('/') for p in listing if p.startswith('/usr/lib/x86_64-linux-gnu/') and '.so.' in p
               and (pathlib.Path('/krb') / p.lstrip('/')).is_file()
               and not (pathlib.Path('/krb') / p.lstrip('/')).is_symlink()]
    assert len(payload) == 1, (name, payload)
    rel = payload[0]
    installed = pathlib.Path('/krb') / rel
    assert installed.is_file() and not installed.is_symlink()
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(installed, target)
    built[rel] = digest(target)
    files[rel] = {'upstream':'krb5', 'sha256':built[rel]}
    previous = next(r for r in records if r.startswith('Package: '+name+'\n'))
    assert '\nSource: krb5\n' in previous and '\nVersion: 1.21.3-5+deb13u1\n' in previous
    records[records.index(previous)] = previous.replace('1.21.3-5+deb13u1', version)
    component = next(p for p in packages if p['Package'] == name)
    component.update(Version=version, Source='krb5', importMode='MIT upstream client library built from signed source')
    # Replace the Debian binary checksum for this actual installed file.
    md5path = name+':amd64.md5sums'
    old = (pathlib.Path('/parent/info') / md5path).read_text().splitlines()
    prefix = hashlib.md5(target.read_bytes()).hexdigest()
    assert sum(line.split()[-1] == rel for line in old) == 1
    (info / md5path).write_text('\n'.join(prefix+'  '+rel if line.split()[-1] == rel else line for line in old)+'\n')
status.write_text('\n\n'.join(records)+'\n')
save('runtime-files.json', files)
save('debian-components.json', packages)
license_file = root/'usr/share/doc/otziv-krb5/NOTICE'
license_file.parent.mkdir(parents=True)
shutil.copyfile('/build/krb5-1.22.3/NOTICE',license_file)
component = {'name':'krb5','version':'1.22.3','packageVersion':version,'purl':'pkg:generic/krb5@1.22.3',
             'url':source,'sha256':source_sha,'signer':'C4493CB739F4A89F9852CBC20CBA08575F8372DF',
             'licenseSha256':digest(license_file),'installedLibraries':built}
save('mit-kerberos.json', component)
sbom['components'].append({'type':'library','name':'krb5','version':'1.22.3','purl':component['purl'],
                           'externalReferences':[{'type':'distribution','url':source,'hashes':[{'alg':'SHA-256','content':source_sha}]}]})
save('upstream.cdx.json', sbom)
